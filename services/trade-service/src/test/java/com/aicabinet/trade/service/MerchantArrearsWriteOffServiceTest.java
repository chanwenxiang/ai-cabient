package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.MerchantWalletAccount;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CB-086 第 4.1 条：欠款核销（平台兜底）门槛。
 *
 * <p>钉住：活跃商户不许核销 / 余额非负不许核销 / 在途提现与在途分账不许核销 /
 * 金额取执行时实际负余额（不接受前端金额）/ 幂等命中不重复记账 / 大额同批发起审批。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MerchantArrearsWriteOffServiceTest {

    private static final String M1 = "M1";
    private static final long OP = 9L;

    @Mock private MerchantMapper merchantMapper;
    @Mock private MerchantWalletService walletService;
    @Mock private MerchantWalletArrearsService arrearsService;
    @Mock private MerchantWithdrawRequestMapper withdrawMapper;
    @Mock private OrderRevenueSplitMapper splitMapper;
    @Mock private PermissionService permissionService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private AdminAuditService auditService;
    @Mock private ApprovalWorkflowService approvalWorkflowService;
    @Mock private WithdrawPolicyResolver policy;
    @Mock private DistributedLockService lockService;

    private MerchantArrearsWriteOffService service;

    @BeforeEach
    void setUp() {
        service = new MerchantArrearsWriteOffService(merchantMapper, walletService, arrearsService,
                withdrawMapper, splitMapper, permissionService, merchantScopeService, auditService,
                approvalWorkflowService, policy, lockService);
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(policy.merchantReviewThresholdCents()).thenReturn(500_000L);
        when(withdrawMapper.selectCount(any())).thenReturn(0L);
        when(splitMapper.selectCount(any())).thenReturn(0L);
        when(walletService.creditIfAbsent(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        // 欠款段固定，便于断言幂等键；未命中时走「今天」兜底
        when(arrearsService.arrearsOf(M1)).thenReturn(java.util.Optional.of(
                new MerchantWalletArrearsService.Arrears(M1, 5_000L, 91, java.time.LocalDate.of(2026, 8, 14))));
    }

    private void merchantWithStatus(String status) {
        Merchant m = new Merchant();
        m.setMerchantId(M1);
        m.setMerchantName("测试商户");
        m.setStatus(status);
        when(merchantMapper.findById(M1)).thenReturn(Optional.of(m));
    }

    private void balance(long cents) {
        MerchantWalletAccount acc = new MerchantWalletAccount();
        acc.setMerchantId(M1);
        acc.setBalanceCents(cents);
        acc.setFrozenCents(0L);
        when(walletService.findAccount(M1)).thenReturn(acc);
    }

    @Test
    void activeMerchant_isRejected() {
        merchantWithStatus("ACTIVE");
        balance(-5_000L);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.writeOff(OP, M1, "坏账"));
        assertEquals(409, e.getStatusCode().value());
        verify(walletService, never()).creditIfAbsent(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void nonNegativeBalance_isRejected() {
        merchantWithStatus("INACTIVE");
        balance(100L);
        assertThrows(ResponseStatusException.class, () -> service.writeOff(OP, M1, "坏账"));
        verify(walletService, never()).creditIfAbsent(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void frozenFunds_blockWriteOff() {
        merchantWithStatus("INACTIVE");
        MerchantWalletAccount acc = new MerchantWalletAccount();
        acc.setMerchantId(M1);
        acc.setBalanceCents(-5_000L);
        acc.setFrozenCents(1_000L);
        when(walletService.findAccount(M1)).thenReturn(acc);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.writeOff(OP, M1, "坏账"));
        assertTrue(e.getReason() != null && e.getReason().contains("冻结"), e.getReason());
    }

    @Test
    void newArrearsEpisode_getsNewIdempotencyKey() {
        merchantWithStatus("INACTIVE");
        balance(-5_000L);
        when(arrearsService.arrearsOf(M1)).thenReturn(java.util.Optional.of(
                new MerchantWalletArrearsService.Arrears(M1, 5_000L, 10, java.time.LocalDate.of(2026, 11, 1))));
        service.writeOff(OP, M1, "第二段欠款");
        // 与 2026-08-14 那一段同金额，但键必须不同，否则第二段会被旧键静默吞掉
        verify(walletService).creditIfAbsent(eq(M1), eq(5_000L),
                eq(MerchantArrearsWriteOffService.ENTRY_TYPE),
                eq(MerchantArrearsWriteOffService.REF_TYPE),
                eq("WO:M1:5000:2026-11-01"), anyString());
    }

    @Test
    void inflightWithdraw_blocksWriteOff() {
        merchantWithStatus("INACTIVE");
        balance(-5_000L);
        when(withdrawMapper.selectCount(any())).thenReturn(1L);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.writeOff(OP, M1, "坏账"));
        assertTrue(e.getReason() != null && e.getReason().contains("在途提现"), e.getReason());
    }

    @Test
    void inflightSplit_blocksWriteOff() {
        merchantWithStatus("INACTIVE");
        balance(-5_000L);
        when(splitMapper.selectCount(any())).thenReturn(2L);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.writeOff(OP, M1, "坏账"));
        assertTrue(e.getReason() != null && e.getReason().contains("在途分账"), e.getReason());
    }

    @Test
    void writesOffExactBalance_withAudit_andDeterministicRef() {
        merchantWithStatus("INACTIVE");
        balance(-5_000L);
        var result = service.writeOff(OP, M1, "撤柜不再合作");
        assertEquals(5_000L, result.amountCents());
        assertFalse(result.alreadyRecorded());
        verify(walletService).creditIfAbsent(eq(M1), eq(5_000L),
                eq(MerchantArrearsWriteOffService.ENTRY_TYPE),
                eq(MerchantArrearsWriteOffService.REF_TYPE),
                eq("WO:M1:5000:2026-08-14"), contains("撤柜不再合作"));
        verify(auditService).appendLog(eq(OP), eq(MerchantArrearsWriteOffService.ENTRY_TYPE),
                eq("MERCHANT_WALLET"), eq(M1), contains("核销金额(分)=5000"));
        verify(approvalWorkflowService, never()).start(anyString(), anyString(), any(), anyString());
    }

    @Test
    void largeAmount_alsoStartsApproval() {
        merchantWithStatus("INACTIVE");
        balance(-600_000L);
        when(policy.merchantReviewThresholdCents()).thenReturn(500_000L);
        service.writeOff(OP, M1, "坏账");
        verify(approvalWorkflowService).start(eq(MerchantArrearsWriteOffService.APPROVAL_BIZ_TYPE),
                eq("WO:" + M1 + ":600000:2026-08-14"), eq(OP), contains("欠款核销"));
    }

    @Test
    void idempotentReplay_doesNotDoubleAudit() {
        merchantWithStatus("INACTIVE");
        balance(-5_000L);
        when(walletService.creditIfAbsent(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);
        var result = service.writeOff(OP, M1, "坏账");
        assertTrue(result.alreadyRecorded());
        verify(auditService, never()).appendLog(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void walletLockBusy_isRejected() {
        merchantWithStatus("INACTIVE");
        balance(-5_000L);
        when(lockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(false);
        assertThrows(ResponseStatusException.class, () -> service.writeOff(OP, M1, "坏账"));
        verify(lockService, never()).unlock(anyString());
    }
}
