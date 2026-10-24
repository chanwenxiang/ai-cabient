package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantWalletOverviewDto;
import com.aicabinet.trade.config.MerchantWithdrawProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.MerchantWalletAccount;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PayoutAccountMapper;
import com.aicabinet.trade.payout.PayoutChannelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UAT §7.17 ①：门户钱包是只读视图，不得建户。
 *
 * <p><b>要修的断链</b>：{@code merchantOverview} 曾调 {@code ensureAccount}（会 INSERT），
 * 而它自己标着 {@code @Transactional(readOnly = true)} ⇒ PG 25006 拒写 ⇒ 没有钱包行的商户
 * 门户钱包页恒 500，GET 还顺带抢了钱包写锁。口径与线长侧一致：缺户按 0 展示，建档/入账路径再 ensure。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("商户钱包只读视图不建户")
class MerchantWalletOverviewReadonlyTest {

    private static final long USER_ID = 100000034L;
    private static final String MERCHANT_ID = "M-1";

    @Mock private MerchantWithdrawRequestMapper withdrawMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private MerchantWalletService merchantWalletService;
    @Mock private MerchantWithdrawPayoutService payoutService;
    @Mock private MerchantFeaturePackService merchantFeaturePackService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private PayoutAccountService payoutAccountService;
    @Mock private PayoutAccountMapper payoutAccountMapper;
    @Mock private PayoutChannelRegistry payoutChannelRegistry;
    @Mock private WithdrawEligibilityService withdrawEligibilityService;
    @Mock private OrderRevenueSplitMapper orderRevenueSplitMapper;
    @Mock private ApprovalWorkflowService approvalWorkflowService;

    private MerchantWithdrawService service;

    @BeforeEach
    void setUp() {
        MerchantWithdrawProperties properties =
                new MerchantWithdrawProperties(true, 100, 1_000_000L, 4_000_000L, 50_000L, 0, 0);
        service = new MerchantWithdrawService(
                withdrawMapper, merchantMapper,
                merchantWalletService, payoutService, properties,
                payoutAccountService, payoutAccountMapper,
                merchantFeaturePackService, merchantScopeService, permissionService, auditService,
                distributedLockService, approvalWorkflowService, WithdrawPolicyResolver.ymlOnly(properties),
                payoutChannelRegistry, orderRevenueSplitMapper, withdrawEligibilityService, null, null, null);
        ReflectionTestUtils.setField(service, "self", service);

        Merchant merchant = new Merchant();
        merchant.setMerchantId(MERCHANT_ID);
        merchant.setMerchantName("测试商户");
        lenient().when(merchantMapper.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
        lenient().when(merchantFeaturePackService.allowedMerchantIdsForPack(USER_ID, MerchantFeaturePacks.BIZ))
                .thenReturn(java.util.Set.of(MERCHANT_ID));
        lenient().when(merchantWalletService.listRecentLedger(MERCHANT_ID, 10)).thenReturn(List.of());
        lenient().when(withdrawMapper.findByMerchantIdOrderByCreatedAtDesc(MERCHANT_ID, 10)).thenReturn(List.of());
        lenient().when(orderRevenueSplitMapper.sumWalletCreditedButNotYetWithdrawable(
                eq(MERCHANT_ID), any())).thenReturn(0L);
    }

    @Test
    void merchantWithoutWalletRow_seesZeroInsteadOfErrorAndNoInsertIsAttempted() {
        when(merchantWalletService.findAccount(MERCHANT_ID)).thenReturn(null);

        MerchantWalletOverviewDto overview = service.merchantOverview(USER_ID, MERCHANT_ID);

        assertTrue(overview.bound());
        assertEquals(MERCHANT_ID, overview.merchantId());
        assertEquals(0L, overview.balanceCents().longValue());
        assertEquals(0L, overview.frozenCents().longValue());
        assertEquals(0L, overview.availableCents().longValue());
        // 这条才是「只读视图不建户」的判据：曾经这里是 ensureAccount ⇒ readOnly 事务里 INSERT
        verify(merchantWalletService, never()).ensureAccount(anyString());
    }

    @Test
    void merchantWithWalletRow_keepsCb035AvailableCopy() {
        MerchantWalletAccount account = new MerchantWalletAccount();
        account.setMerchantId(MERCHANT_ID);
        account.setBalanceCents(10_000L);
        account.setFrozenCents(2_000L);
        when(merchantWalletService.findAccount(MERCHANT_ID)).thenReturn(account);
        when(orderRevenueSplitMapper.sumWalletCreditedButNotYetWithdrawable(eq(MERCHANT_ID), any()))
                .thenReturn(3_000L);

        MerchantWalletOverviewDto overview = service.merchantOverview(USER_ID, null);

        assertEquals(10_000L, overview.balanceCents().longValue());
        assertEquals(2_000L, overview.frozenCents().longValue());
        assertEquals(3_000L, overview.pendingSettleCents().longValue());
        assertEquals(5_000L, overview.availableCents().longValue());
        verify(merchantWalletService, never()).ensureAccount(anyString());
    }
}
