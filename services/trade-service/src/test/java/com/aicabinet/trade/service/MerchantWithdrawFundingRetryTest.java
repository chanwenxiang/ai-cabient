package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantWithdrawRequestDto;
import com.aicabinet.trade.config.MerchantWithdrawProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.MerchantWithdrawRequest;
import com.aicabinet.trade.domain.PayoutAccount;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PayoutAccountMapper;
import com.aicabinet.trade.payout.PayoutChannelRegistry;
import com.aicabinet.trade.payout.PayoutConfirmTicketStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MerchantWithdrawFundingRetryTest {

    @Mock private MerchantWithdrawRequestMapper withdrawMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private MerchantWalletService merchantWalletService;
    @Mock private MerchantWithdrawPayoutService payoutService;
    @Mock private PayoutAccountService payoutAccountService;
    @Mock private PayoutAccountMapper payoutAccountMapper;
    @Mock private MerchantFeaturePackService merchantFeaturePackService;
    @Mock private MerchantWalletArrearsService walletArrearsService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private ApprovalWorkflowService approvalWorkflowService;
    @Mock private PayoutChannelRegistry payoutChannelRegistry;
    @Mock private OrderRevenueSplitMapper orderRevenueSplitMapper;
    @Mock private WithdrawEligibilityService withdrawEligibilityService;
    @Mock private PlatformFundService platformFundService;
    @Mock private SystemConfigService systemConfigService;
    @Mock private PayoutConfirmTicketStore confirmTicketStore;

    private MerchantWithdrawService service;

    @BeforeEach
    void setUp() {
        MerchantWithdrawProperties properties =
                new MerchantWithdrawProperties(false, 100, 0L, 500_000, 50_000, 0, 0);
        service = new MerchantWithdrawService(
                withdrawMapper, merchantMapper, merchantWalletService, payoutService, properties,
                payoutAccountService, payoutAccountMapper, merchantFeaturePackService, walletArrearsService,
                merchantScopeService, permissionService, auditService, distributedLockService,
                approvalWorkflowService, WithdrawPolicyResolver.ymlOnly(properties), payoutChannelRegistry,
                orderRevenueSplitMapper, withdrawEligibilityService, platformFundService, systemConfigService,
                confirmTicketStore, null);
        ReflectionTestUtils.setField(service, "self", service);
        stubPayeeAndMerchant();
        lenient().when(distributedLockService.tryLock(
                MerchantWithdrawService.merchantWalletLockKey("M-1"), 60L, 5L)).thenReturn(true);
        lenient().when(withdrawMapper.claimForPayout(anyLong(), anyString(), any())).thenReturn(1);
    }

    @Test
    void fundingRetry_usesChannelAuthorityAndSkipsLocalFundGate() {
        MerchantWithdrawRequest request = candidate("APPROVED");
        when(withdrawMapper.findFundingRetryCandidates(any())).thenReturn(List.of(request));
        when(withdrawMapper.findById(1L)).thenReturn(Optional.of(request));
        when(payoutService.payout(any(), any(), any())).thenReturn(
                MerchantWithdrawPayoutService.PayoutResult.success("WECHAT", "TX-1", "成功"));

        int attempted = service.retryFundingBlockedWithdraws();

        assertEquals(1, attempted);
        assertEquals("PAID", request.getStatus());
        verify(platformFundService, never()).gatePayout(anyString(), anyLong(), anyLong());
        verify(payoutService).payout(any(), any(), any());
        verify(merchantWalletService).consumeFrozenSplit(eq("M-1"), eq(1_000L), eq(0L),
                eq("WITHDRAW"), eq("1"), anyString());
        verify(withdrawMapper).finalizeIfPaying(eq(1L), eq("PAID"), any(), eq("TX-1"), eq("成功"));
    }

    @Test
    void fundingRetry_notEnoughReschedulesOneHourAndRefreezesFailedOrder() {
        MerchantWithdrawRequest request = candidate("FAILED");
        when(withdrawMapper.findFundingRetryCandidates(any())).thenReturn(List.of(request));
        when(withdrawMapper.findById(1L)).thenReturn(Optional.of(request));
        when(payoutService.payout(any(), any(), any())).thenReturn(
                MerchantWithdrawPayoutService.PayoutResult.failure("WECHAT", null,
                        "微信转账失败 code=NOT_ENOUGH 运营账户余额不足"));

        service.retryFundingBlockedWithdraws();

        assertEquals("FAILED", request.getStatus());
        assertNotNull(request.getNextPayoutRetryAt());
        assertEquals(1, request.getPayoutRetryCount());
        verify(merchantWalletService).freezeForWithdraw(eq("M-1"), eq(1_000L),
                eq("WITHDRAW"), eq("1"), anyString());
        verify(merchantWalletService).releaseFrozen(eq("M-1"), eq(1_000L),
                eq("WITHDRAW"), eq("1"), anyString());
        verify(withdrawMapper).markPayoutFailed(eq(1L), any(), isNull(), anyString(),
                notNull(), notNull());
    }

    @Test
    void fundingRetry_nonFundingFailureStopsAutomaticRetry() {
        MerchantWithdrawRequest request = candidate("APPROVED");
        when(withdrawMapper.findFundingRetryCandidates(any())).thenReturn(List.of(request));
        when(withdrawMapper.findById(1L)).thenReturn(Optional.of(request));
        when(payoutService.payout(any(), any(), any())).thenReturn(
                MerchantWithdrawPayoutService.PayoutResult.failure("WECHAT", null,
                        "微信转账失败 code=USER_ERROR 收款用户不存在"));

        service.retryFundingBlockedWithdraws();

        assertEquals("FAILED", request.getStatus());
        assertNull(request.getNextPayoutRetryAt());
        assertNull(request.getLastPayoutBlockReason());
        verify(withdrawMapper).markPayoutFailed(eq(1L), any(), isNull(), anyString(),
                isNull(), isNull());
    }

    @Test
    void manualPayout_blockedByLocalFundLedgerSchedulesChannelRetry() {
        MerchantWithdrawRequest request = candidate("APPROVED");
        when(withdrawMapper.findById(1L)).thenReturn(Optional.of(request));
        when(platformFundService.gatePayout("WECHAT", 1_000L, 1L))
                .thenReturn(Optional.of("平台备付金不足"));

        MerchantWithdrawRequestDto dto = service.executePayout(1L);

        assertEquals("APPROVED", dto.status());
        verify(withdrawMapper).markFundingBlocked(eq(1L), eq("平台备付金不足"), any(), any());
        verify(payoutService, never()).payout(any(), any(), any());
    }

    private MerchantWithdrawRequest candidate(String status) {
        MerchantWithdrawRequest request = new MerchantWithdrawRequest();
        request.setRequestId(1L);
        request.setRequestNo("REQ-1");
        request.setMerchantId("M-1");
        request.setMerchantName("测试商户");
        request.setAmountCents(1_000L);
        request.setFeeCents(0L);
        request.setStatus(status);
        request.setPayChannel("WECHAT");
        request.setPayoutAccountId(1L);
        request.setIdemKey("MW0000000001");
        request.setLastPayoutBlockReason("NOT_ENOUGH");
        request.setNextPayoutRetryAt(Instant.now().minusSeconds(1));
        request.setPayoutRetryCount(0);
        return request;
    }

    private void stubPayeeAndMerchant() {
        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setMerchantName("测试商户");
        when(merchantMapper.findById("M-1")).thenReturn(Optional.of(merchant));
        PayoutAccount account = new PayoutAccount();
        account.setAccountId(1L);
        account.setChannel("WECHAT");
        when(payoutAccountMapper.findByAccountId(1L)).thenReturn(Optional.of(account));
    }
}
