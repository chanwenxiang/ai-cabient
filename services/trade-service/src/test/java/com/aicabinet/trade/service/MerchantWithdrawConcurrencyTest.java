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
import com.aicabinet.trade.domain.PayoutAccount;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PayoutAccountMapper;
import com.aicabinet.trade.payout.PayoutChannelRegistry;
import com.aicabinet.trade.payout.PayoutConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;
    /**
     * V307：申请时必须锁定收款账户（快照），故并发测试也要提供收款账户服务。
     *
     * <p>🔴 不用「半装配」测法：曾一度让生产服务在 {@code payoutAccountService == null} 时
     * 抛 500 兜底，结果这几个用例全被兜底拦掉 —— 测的是「兜底生效」而非「提现流程正确」。
     * 正确做法是注入 mock 并让它返回真实账户，让用例继续走完整路径。</p>
     */
    @Mock private PayoutAccountService payoutAccountService;
    @Mock private PayoutAccountMapper payoutAccountMapper;
    /**
     * V308：渠道硬限额校验（单笔 / 单收款人单日 / 单通道当日总额）。
     *
     * <p>本组的收款账户走 {@link PayoutConstants#PAY_CHANNEL_BANK}（对公代付），
     * 其 {@code channelDailyLimitCents()} 恒0 ⇒ 银行三维度全不限，
     * 因此这里给一个<b>空注册表</b>（find 返空 ⇒ 跳过渠道限额）即可，
     * 限额行为由 {@code MerchantWithdrawChannelLimitTest} 单独覆盖。</p>
     */
    @Mock private PayoutChannelRegistry payoutChannelRegistry;
    /** V321 提现资质门禁。 */
    @Mock private WithdrawEligibilityService withdrawEligibilityService;
    @Mock private OrderRevenueSplitMapper orderRevenueSplitMapper;

    private MerchantWithdrawService service;

    @BeforeEach
    void setUp() {
        // V307：第 3 位是新增的 maxAmountCents（0 = 单笔不限）——
        // 🔴 不能靠给 MerchantWithdrawProperties 加 6 参重载来让这里少写一个参数：
        //    @ConfigurationProperties 的 record 一旦有多个构造器，Spring 的构造器绑定
        //    就无法确定用哪个（实测抛 "No default constructor found"，整个应用上下文起不来）。
        MerchantWithdrawProperties properties =
                new MerchantWithdrawProperties(true, 100, 0L, 500_000, 50_000, 0, 0);
        service = new MerchantWithdrawService(
                withdrawMapper, merchantMapper, accountMapper, ledgerMapper,
                merchantWalletService, payoutService, properties,
                payoutAccountService, payoutAccountMapper,
                merchantFeaturePackService, merchantScopeService, permissionService, auditService,
                distributedLockService, null, WithdrawPolicyResolver.ymlOnly(properties),
                payoutChannelRegistry, orderRevenueSplitMapper, withdrawEligibilityService, null);
        ReflectionTestUtils.setField(service, "self", service);

        // V307：所有用例共用一个可用的收款账户（默认对公 + BANK 通道）。
        // 放在 setUp 而非各用例里：这几个用例测的是「并发/锁/冻结」语义，
        // 收款方只是前置条件，逐用例重复 stub 只会淹没真正的断言。
        //
        // 🔴 用 lenient()：部分用例（锁冲突、驳回、陈旧扫描）**不会**走到申请落库，
        //    严格模式下这类未被消费的 stub 会报 UnnecessaryStubbing 而让用例变红。
        lenient().when(payoutAccountService.resolveForApply(any(), any(), any()))
                .thenReturn(stubPayeeAccount());
    }

    /**
     * 造一个可用的收款账户（BANK 对公），让「申请时锁定收款方」路径能走通。
     *
     * <p>只填与快照/打款相关的字段：{@code accountId}（快照回填）、{@code channel}（决定打款通道）、
     * {@code accountNoMask}（快照列，测试断言只看这个掩码）。
     */
    private static PayoutAccount stubPayeeAccount() {
        PayoutAccount account = new PayoutAccount();
        account.setAccountId(1L);
        account.setOwnerType(PayoutConstants.PAYEE_OWNER_MERCHANT);
        account.setAccountType(PayoutConstants.PAYEE_TYPE_COMPANY);
        account.setChannel(PayoutConstants.PAY_CHANNEL_BANK);
        account.setAccountName("测试商户有限公司");
        account.setAccountNo("6222021234567890123");
        account.setAccountNoMask("****0123");
        account.setStatus("ACTIVE");
        account.setIsDefault(true);
        return account;
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
                () -> service.apply(1L, "M-1", 10_000L, "REQ-1"));

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

        AtomicReference<MerchantWithdrawRequest> stored = new AtomicReference<>();
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
            stored.set(req);
            return 1;
        });
        when(withdrawMapper.findById(99L)).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(payoutService.payout(any(), eq(merchant), any())).thenReturn(
                new MerchantWithdrawPayoutService.PayoutResult(true, "MOCK", "PAY-1", "ok"));

        MerchantWithdrawRequestDto dto = service.apply(1L, "M-1", 10_000L, "REQ-2");

        assertEquals("PAID", dto.status());
        verify(merchantWalletService).freezeForWithdraw(eq("M-1"), eq(10_000L), any(), any(), any());
        verify(distributedLockService).unlock(MerchantWithdrawService.merchantWalletLockKey("M-1"));
    }

    /** W6(H26): 打款失败即释放冻结，不得 consumeFrozen；CANCELLED 后不再重复打款。 */
    @Test
    void apply_payoutFailed_releasesFrozen_cancelFinalizes() {
        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setMerchantName("商户1");

        MerchantWalletAccount account = new MerchantWalletAccount();
        account.setMerchantId("M-1");
        account.setBalanceCents(100_000L);
        account.setFrozenCents(0L);

        AtomicReference<MerchantWithdrawRequest> stored = new AtomicReference<>();
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
            stored.set(req);
            return 1;
        });
        when(withdrawMapper.findById(77L)).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(payoutService.payout(any(), eq(merchant), any())).thenReturn(
                MerchantWithdrawPayoutService.PayoutResult.failure("WECHAT", null, "转账未接入"));

        MerchantWithdrawRequestDto failed = service.apply(1L, "M-1", 10_000L, "REQ-FAIL");

        assertEquals("FAILED", failed.status());
        verify(merchantWalletService).freezeForWithdraw(eq("M-1"), eq(10_000L), any(), any(), any());
        // H26：FAILED 落账同事务即解冻
        verify(merchantWalletService).releaseFrozen(eq("M-1"), eq(10_000L), eq("WITHDRAW"), eq("77"), anyString());
        verify(merchantWalletService, never()).consumeFrozen(anyString(), anyLong(), anyString(), anyString(), anyString());

        MerchantWithdrawRequestDto cancelled = service.cancelFailed(1L, 77L, "放弃打款");

        assertEquals("CANCELLED", cancelled.status());
        verify(merchantWalletService, never()).consumeFrozen(anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    /** H26：从 FAILED 重试打款，markPaying 需重新冻结后再打款。 */
    @Test
    void payoutRetryFromFailed_refreezesBeforePaying() {
        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setMerchantName("商户1");

        MerchantWithdrawRequest request = new MerchantWithdrawRequest();
        request.setRequestId(88L);
        request.setRequestNo("REQ-RETRY");
        request.setMerchantId("M-1");
        request.setAmountCents(10_000L);
        request.setStatus("FAILED");

        when(withdrawMapper.findById(88L)).thenReturn(Optional.of(request));
        when(merchantMapper.findById("M-1")).thenReturn(Optional.of(merchant));
        when(distributedLockService.tryLock(
                MerchantWithdrawService.merchantWalletLockKey("M-1"), 60L, 5L))
                .thenReturn(true);
        when(payoutService.payout(any(), eq(merchant), any())).thenReturn(
                new MerchantWithdrawPayoutService.PayoutResult(true, "MOCK", "PAY-2", "ok"));

        MerchantWithdrawRequestDto dto = service.payout(1L, 88L);

        assertEquals("PAID", dto.status());
        // FAILED → PAYING 时重新冻结 + PAID 时 consume
        verify(merchantWalletService).freezeForWithdraw(eq("M-1"), eq(10_000L), eq("WITHDRAW"), eq("88"), anyString());
        // V308：打款成功改走拆分记账（净额 + 手续费单列）。本单 feeCents 未设置 ⇒ 0 ⇒
        // 拆分后仍等价于「扣毛额」，但调用的是 consumeFrozenSplit。
        verify(merchantWalletService).consumeFrozenSplit(eq("M-1"), eq(10_000L), eq(0L),
                eq("WITHDRAW"), eq("88"), anyString());
        verify(merchantWalletService, never()).consumeFrozen(anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    /** H38：PAYING 超过阈值的 MOCK 提现单被兜底置 FAILED 并解冻。 */
    @Test
    void failStalePayingWithdraws_marksFailedAndReleases() {
        MerchantWithdrawRequest stale = new MerchantWithdrawRequest();
        stale.setRequestId(66L);
        stale.setRequestNo("REQ-STALE");
        stale.setMerchantId("M-1");
        stale.setAmountCents(10_000L);
        stale.setStatus("PAYING");
        stale.setPayChannel("MOCK");

        when(withdrawMapper.findByStatusAndUpdatedAtBefore(eq("PAYING"), any())).thenReturn(List.of(stale));
        when(withdrawMapper.findById(66L)).thenReturn(Optional.of(stale));
        when(distributedLockService.tryLock(
                MerchantWithdrawService.merchantWalletLockKey("M-1"), 60L, 5L))
                .thenReturn(true);

        assertEquals(1, service.failStalePayingWithdraws());

        assertEquals("FAILED", stale.getStatus());
        verify(merchantWalletService).releaseFrozen(eq("M-1"), eq(10_000L), eq("WITHDRAW"), eq("66"), anyString());
    }

    /** F3：真实渠道 PAYING 超时禁止自动置失败（防「已出款+已解冻」双重支出），转人工核对。 */
    @Test
    void failStalePayingWithdraws_realChannel_skipsAutoFail() {
        MerchantWithdrawRequest stale = new MerchantWithdrawRequest();
        stale.setRequestId(67L);
        stale.setRequestNo("REQ-STALE-REAL");
        stale.setMerchantId("M-1");
        stale.setAmountCents(10_000L);
        stale.setStatus("PAYING");
        stale.setPayChannel("WECHAT");

        when(withdrawMapper.findByStatusAndUpdatedAtBefore(eq("PAYING"), any())).thenReturn(List.of(stale));
        when(withdrawMapper.findById(67L)).thenReturn(Optional.of(stale));
        when(distributedLockService.tryLock(
                MerchantWithdrawService.merchantWalletLockKey("M-1"), 60L, 5L))
                .thenReturn(true);

        assertEquals(0, service.failStalePayingWithdraws());

        assertEquals("PAYING", stale.getStatus());
        verify(merchantWalletService, never()).releaseFrozen(anyString(), anyLong(), anyString(), anyString(), anyString());
        verify(auditService).appendLog(eq(0L), eq("MERCHANT_WITHDRAW_PAYOUT_STALE_MANUAL"),
                eq("MERCHANT_WITHDRAW"), eq("67"), anyString());
    }

    /** H53：绑定多个商户且未指定 merchantId 时拒绝申请，显式指定合法商户可提现。 */
    @Test
    void merchantApply_multiBound_requiresExplicitMerchantId() {
        when(merchantFeaturePackService.allowedMerchantIdsForPack(9L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of("M-1", "M-2"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.merchantApply(9L, 10_000L, "REQ-M1", null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-2");
        merchant.setMerchantName("商户2");
        when(merchantMapper.findById("M-2")).thenReturn(Optional.of(merchant));
        when(distributedLockService.tryLock(
                MerchantWithdrawService.merchantWalletLockKey("M-2"), 60L, 5L))
                .thenReturn(true);
        when(withdrawMapper.findByRequestNo("REQ-M2")).thenReturn(Optional.empty());
        MerchantWalletAccount account = new MerchantWalletAccount();
        account.setMerchantId("M-2");
        account.setBalanceCents(100_000L);
        account.setFrozenCents(0L);
        when(merchantWalletService.ensureAccount("M-2")).thenReturn(account);
        when(withdrawMapper.sumAmountByMerchantSince(eq("M-2"), any())).thenReturn(0L);
        AtomicReference<MerchantWithdrawRequest> stored = new AtomicReference<>();
        when(withdrawMapper.insert(any())).thenAnswer(inv -> {
            MerchantWithdrawRequest req = inv.getArgument(0);
            req.setRequestId(101L);
            stored.set(req);
            return 1;
        });
        when(withdrawMapper.findById(101L)).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(payoutService.payout(any(), eq(merchant), any())).thenReturn(
                new MerchantWithdrawPayoutService.PayoutResult(true, "MOCK", "PAY-M2", "ok"));

        MerchantWithdrawRequestDto dto = service.merchantApply(9L, 10_000L, "REQ-M2", "M-2");
        assertEquals("PAID", dto.status());
        assertEquals("M-2", dto.merchantId());
    }

    /** H53：显式指定未绑定的商户被拒绝。 */
    @Test
    void merchantApply_explicitForeignMerchant_rejected() {
        when(merchantFeaturePackService.allowedMerchantIdsForPack(9L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of("M-1"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.merchantApply(9L, 10_000L, "REQ-X", "M-9"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }
}
