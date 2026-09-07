package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantWithdrawRequestDto;
import com.aicabinet.trade.config.MerchantWithdrawProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.MerchantWalletAccount;
import com.aicabinet.trade.domain.MerchantWithdrawRequest;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantWalletAccountMapper;
import com.aicabinet.trade.mapper.MerchantWalletLedgerMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantWithdrawConcurrencyTest {

    @Mock private MerchantWithdrawRequestMapper withdrawMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private MerchantWalletAccountMapper accountMapper;
    @Mock private MerchantWalletLedgerMapper ledgerMapper;
    @Mock private MerchantWalletService merchantWalletService;
    @Mock private MerchantWithdrawPayoutService payoutService;
    @Mock private MerchantFeaturePackService merchantFeaturePackService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private MerchantWithdrawService service;

    @BeforeEach
    void setUp() {
        MerchantWithdrawProperties properties = new MerchantWithdrawProperties(true, 100, 500_000, 50_000, 0, 0);
        service = new MerchantWithdrawService(
                withdrawMapper, merchantMapper, accountMapper, ledgerMapper,
                merchantWalletService, payoutService, properties,
                merchantFeaturePackService, permissionService, auditService,
                distributedLockService, null);
    }

    @Test
    void apply_whenLockBusy_rejectsWithConflict() {
        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setMerchantName("商户1");
        when(merchantMapper.findById("M-1")).thenReturn(Optional.of(merchant));
        when(distributedLockService.tryLock(
                MerchantWithdrawService.merchantWalletLockKey("M-1"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.apply("M-1", 10_000L, "REQ-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void apply_acquiresLockAndFreezesBalance() {
        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setMerchantName("商户1");

        MerchantWalletAccount account = new MerchantWalletAccount();
        account.setMerchantId("M-1");
        account.setBalanceCents(100_000L);
        account.setFrozenCents(0L);

        when(merchantMapper.findById("M-1")).thenReturn(Optional.of(merchant));
        when(distributedLockService.tryLock(
                MerchantWithdrawService.merchantWalletLockKey("M-1"), 60L, 5L))
                .thenReturn(true);
        when(withdrawMapper.findByRequestNo("REQ-2")).thenReturn(Optional.empty());
        when(merchantWalletService.ensureAccount("M-1")).thenReturn(account);
        when(withdrawMapper.sumAmountByMerchantSince(eq("M-1"), any())).thenReturn(0L);
        when(withdrawMapper.insert(any())).thenAnswer(inv -> {
            MerchantWithdrawRequest req = inv.getArgument(0);
            req.setRequestId(99L);
            return 1;
        });
        when(payoutService.payout(any(), eq(merchant))).thenReturn(
                new MerchantWithdrawPayoutService.PayoutResult(true, "MOCK", "PAY-1", "ok"));

        MerchantWithdrawRequestDto dto = service.apply("M-1", 10_000L, "REQ-2");

        assertEquals("PAID", dto.status());
        verify(merchantWalletService).freezeForWithdraw(eq("M-1"), eq(10_000L), any(), any(), any());
        verify(distributedLockService).unlock(MerchantWithdrawService.merchantWalletLockKey("M-1"));
    }

    /** W6: 打款失败保留冻结；cancelFailed 才 releaseFrozen，且不得 consumeFrozen。 */
    @Test
    void apply_payoutFailed_keepsFreeze_cancelReleases() {
        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setMerchantName("商户1");

        MerchantWalletAccount account = new MerchantWalletAccount();
        account.setMerchantId("M-1");
        account.setBalanceCents(100_000L);
        account.setFrozenCents(0L);

        when(merchantMapper.findById("M-1")).thenReturn(Optional.of(merchant));
        when(distributedLockService.tryLock(
                MerchantWithdrawService.merchantWalletLockKey("M-1"), 60L, 5L))
                .thenReturn(true);
        when(withdrawMapper.findByRequestNo("REQ-FAIL")).thenReturn(Optional.empty());
        when(merchantWalletService.ensureAccount("M-1")).thenReturn(account);
        when(withdrawMapper.sumAmountByMerchantSince(eq("M-1"), any())).thenReturn(0L);
        when(withdrawMapper.insert(any())).thenAnswer(inv -> {
            MerchantWithdrawRequest req = inv.getArgument(0);
            req.setRequestId(77L);
            return 1;
        });
        when(payoutService.payout(any(), eq(merchant))).thenReturn(
                MerchantWithdrawPayoutService.PayoutResult.failure("WECHAT", null, "转账未接入"));

        MerchantWithdrawRequestDto failed = service.apply("M-1", 10_000L, "REQ-FAIL");

        assertEquals("FAILED", failed.status());
        verify(merchantWalletService).freezeForWithdraw(eq("M-1"), eq(10_000L), any(), any(), any());
        verify(merchantWalletService, never()).consumeFrozen(anyString(), anyLong(), anyString(), anyString(), anyString());
        verify(merchantWalletService, never()).releaseFrozen(anyString(), anyLong(), anyString(), anyString(), anyString());

        MerchantWithdrawRequest stored = new MerchantWithdrawRequest();
        stored.setRequestId(77L);
        stored.setMerchantId("M-1");
        stored.setAmountCents(10_000L);
        stored.setStatus("FAILED");
        when(withdrawMapper.findById(77L)).thenReturn(Optional.of(stored));

        MerchantWithdrawRequestDto cancelled = service.cancelFailed(1L, 77L, "放弃打款");

        assertEquals("CANCELLED", cancelled.status());
        verify(merchantWalletService).releaseFrozen(eq("M-1"), eq(10_000L), eq("WITHDRAW"), eq("77"), anyString());
        verify(merchantWalletService, never()).consumeFrozen(anyString(), anyLong(), anyString(), anyString(), anyString());
    }
}
