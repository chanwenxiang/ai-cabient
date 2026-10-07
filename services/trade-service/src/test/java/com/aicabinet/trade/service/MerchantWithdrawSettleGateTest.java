package com.aicabinet.trade.service;

import com.aicabinet.trade.config.MerchantWithdrawProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.MerchantWalletAccount;
import com.aicabinet.trade.domain.MerchantWithdrawRequest;
import com.aicabinet.trade.domain.PayoutAccount;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantWalletAccountMapper;
import com.aicabinet.trade.mapper.MerchantWalletLedgerMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PayoutAccountMapper;
import com.aicabinet.trade.payout.PayoutChannelRegistry;
import com.aicabinet.trade.payout.PayoutConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * V308：T+1 可提现闸门。
 *
 * <p><b>要修的断链</b>：{@code settleAfter}（T+1）此前只是展示字段 —— 分账一落库
 * 就把商户份额打进钱包，提现校验只看「余额 −冻结」，于是商户能<b>当天提走昨天货款</b>，
 * T+1 的风控意义（给退款/争议留窗口）完全失效。
 *
 * <p><b>修法</b>：提现校验时额外扣减「已入钱包但 {@code settleAfter > 今日}」的金额。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("V308 T+1 提现闸门")
class MerchantWithdrawSettleGateTest {

    @Mock private MerchantWithdrawRequestMapper withdrawMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private MerchantWalletAccountMapper accountMapper;
    @Mock private MerchantWalletLedgerMapper ledgerMapper;
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
    /** V321 提现资质门禁。 */
    @Mock private WithdrawEligibilityService withdrawEligibilityService;
    @Mock private OrderRevenueSplitMapper orderRevenueSplitMapper;
    @Mock private ApprovalWorkflowService approvalWorkflowService;

    private MerchantWithdrawService service;

    @BeforeEach
    void setUp() {
        MerchantWithdrawProperties properties =
                new MerchantWithdrawProperties(true, 100, 1_000_000L, 4_000_000L, 50_000L, 0, 0);
        service = new MerchantWithdrawService(
                withdrawMapper, merchantMapper, accountMapper, ledgerMapper,
                merchantWalletService, payoutService, properties,
                payoutAccountService, payoutAccountMapper,
                merchantFeaturePackService, merchantScopeService, permissionService, auditService,
                distributedLockService, approvalWorkflowService, WithdrawPolicyResolver.ymlOnly(properties),
                payoutChannelRegistry, orderRevenueSplitMapper, withdrawEligibilityService, null);
        ReflectionTestUtils.setField(service, "self", service);

        PayoutAccount account = new PayoutAccount();
        account.setAccountId(9L);
        account.setOwnerType(PayoutConstants.PAYEE_OWNER_MERCHANT);
        account.setAccountType(PayoutConstants.PAYEE_TYPE_COMPANY);
        account.setChannel(PayoutConstants.PAY_CHANNEL_BANK);
        account.setStatus("ACTIVE");
        account.setIsDefault(true);
        lenient().when(payoutAccountService.resolveForApply(any(), any(), any())).thenReturn(account);
        lenient().when(distributedLockService.tryLock(anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        lenient().when(withdrawMapper.findByRequestNo(anyString())).thenReturn(Optional.empty());
        lenient().when(withdrawMapper.sumAmountByMerchantSince(eq("M-1"), any())).thenReturn(0L);
        lenient().when(withdrawMapper.insert(any(MerchantWithdrawRequest.class))).thenAnswer(inv -> {
            MerchantWithdrawRequest r = inv.getArgument(0);
            r.setRequestId(1L);
            return 1;
        });
    }

    private void wallet(long balance, long frozen) {
        MerchantWalletAccount a = new MerchantWalletAccount();
        a.setMerchantId("M-1");
        a.setBalanceCents(balance);
        a.setFrozenCents(frozen);
        lenient().when(merchantWalletService.ensureAccount("M-1")).thenReturn(a);
    }

    private void pendingSettle(long cents) {
        lenient().when(orderRevenueSplitMapper.sumWalletCreditedButNotYetWithdrawable(
                eq("M-1"), any(LocalDate.class))).thenReturn(cents);
    }

    private Merchant merchant() {
        Merchant m = new Merchant();
        m.setMerchantId("M-1");
        m.setMerchantName("商户1");
        return m;
    }

    // ==================== 闸门本体 ====================

    @Test
    @DisplayName("🔴 有待结算（T+1 未到）金额时应被拦——这正是修掉的那个洞")
    void pendingSettleBlocksWithdraw() {
        wallet(100_000L, 0L);      // 余额 ¥1000
        pendingSettle(80_000L);    // 其中 ¥800 昨天刚入账，明天才可提

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.persistWithdrawApplication(merchant(), 30_000L, "R-1", null));
        assertEquals(HttpStatus.PRECONDITION_FAILED, e.getStatusCode());
        // 可提现 = 1000 − 800 = ¥200 < 申请 ¥300 ⇒ 拒
        assertTrue(e.getMessage().contains("待结算"),
                "文案应说明有多少钱待结算: " + e.getMessage());
    }

    @Test
    @DisplayName("恰好用完可提现额（¥200）应通过")
    void exactlyAvailable_passes() {
        wallet(100_000L, 0L);
        pendingSettle(80_000L);
        assertDoesNotThrow(() -> service.persistWithdrawApplication(merchant(), 20_000L, "R-2", null));
    }

    @Test
    @DisplayName("超 1 分（¥200.01）应被拒")
    void oneCentOverAvailable_rejected() {
        wallet(100_000L, 0L);
        pendingSettle(80_000L);
        assertThrows(ResponseStatusException.class,
                () -> service.persistWithdrawApplication(merchant(), 20_001L, "R-3", null));
    }

    @Test
    @DisplayName("无待结算时行为与接入前一致（余额多少就能提多少）")
    void noPendingSettle_behavesAsBefore() {
        wallet(100_000L, 0L);
        pendingSettle(0L);
        assertDoesNotThrow(() -> service.persistWithdrawApplication(merchant(), 100_000L, "R-4", null));
    }

    @Test
    @DisplayName("闸门与既有冻结叠加：冻结 600 + 待结算 800，余额 1000 ⇒ 可提仅 0")
    void combinesWithExistingFreeze() {
        wallet(100_000L, 60_000L);
        pendingSettle(80_000L);
        // 1000 − 600(冻结) − 800(待结算) = -400 ⇒ 连1 分都提不了
        assertThrows(ResponseStatusException.class,
                () -> service.persistWithdrawApplication(merchant(), 100L, "R-5", null));
    }

    @Test
    @DisplayName("闸门口径按 Asia/Shanghai 今日（不是 UTC），避免跨时区错判一天")
    void gateUsesShanghaiToday() {
        wallet(100_000L, 0L);
        org.mockito.ArgumentCaptor<LocalDate> captor = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        lenient().when(orderRevenueSplitMapper.sumWalletCreditedButNotYetWithdrawable(
                eq("M-1"), captor.capture())).thenReturn(0L);

        service.persistWithdrawApplication(merchant(), 10_000L, "R-6", null);

        LocalDate expected = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        assertEquals(expected, captor.getValue());
    }
}
