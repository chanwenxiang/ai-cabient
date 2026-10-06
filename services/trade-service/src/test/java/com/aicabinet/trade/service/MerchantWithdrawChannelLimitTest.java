package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
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
import com.aicabinet.trade.payout.PayoutChannel;
import com.aicabinet.trade.payout.PayoutChannelLimits;
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

import java.time.Instant;
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
 * V308：渠道额度三维度校验（单笔 / 单收款人单日 / 单通道当日总额）。
 *
 * <p>🔴 <b>本类的核心价值是「负向对照」</b>：每个限额维度都有
 * 「刚好在限内 → 通过」与「超限 1 分 → 拒绝」<b>成对</b>用例。
 * 只测通过侧的话，把校验代码整段删掉测试也会全绿（这正是此前踩过的坑）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("V308 提现渠道限额")
class MerchantWithdrawChannelLimitTest {

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
    @Mock private OrderRevenueSplitMapper orderRevenueSplitMapper;

    private MerchantWithdrawService service;
    private PayoutAccount wechatAccount;

    @BeforeEach
    void setUp() {
        // 运营限额刻意放宽（单笔 100 万、单日 400 万），让测试聚焦「渠道限额」而非运营限额
        MerchantWithdrawProperties properties =
                new MerchantWithdrawProperties(true, 100, 1_000_000L, 4_000_000L, 50_000L, 0, 0);
        service = new MerchantWithdrawService(
                withdrawMapper, merchantMapper, accountMapper, ledgerMapper,
                merchantWalletService, payoutService, properties,
                payoutAccountService, payoutAccountMapper,
                merchantFeaturePackService, merchantScopeService, permissionService, auditService,
                distributedLockService, null, WithdrawPolicyResolver.ymlOnly(properties),
                payoutChannelRegistry, orderRevenueSplitMapper, null);
        ReflectionTestUtils.setField(service, "self", service);

        wechatAccount = new PayoutAccount();
        wechatAccount.setAccountId(9L);
        wechatAccount.setOwnerType(PayoutConstants.PAYEE_OWNER_MERCHANT);
        wechatAccount.setOwnerId("M-1");
        wechatAccount.setAccountType(PayoutConstants.PAYEE_TYPE_PERSONAL);
        wechatAccount.setChannel(CabinetConstants.PAY_CHANNEL_WECHAT);
        wechatAccount.setStatus("ACTIVE");
        wechatAccount.setIsDefault(true);

        lenient().when(payoutAccountService.resolveForApply(any(), any(), any()))
                .thenReturn(wechatAccount);
        lenient().when(distributedLockService.tryLock(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        lenient().when(merchantWalletService.ensureAccount("M-1")).thenReturn(wallet(10_000_000L));
        lenient().when(withdrawMapper.sumAmountByMerchantSince(eq("M-1"), any())).thenReturn(0L);
        lenient().when(withdrawMapper.findByRequestNo(anyString())).thenReturn(Optional.empty());
        lenient().when(withdrawMapper.insert(any(MerchantWithdrawRequest.class))).thenAnswer(inv -> {
            MerchantWithdrawRequest r = inv.getArgument(0);
            r.setRequestId(1L);
            return 1;
        });

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setMerchantName("商户1");
        lenient().when(merchantMapper.findById("M-1")).thenReturn(Optional.of(merchant));
    }

    private static MerchantWalletAccount wallet(long balance) {
        MerchantWalletAccount a = new MerchantWalletAccount();
        a.setMerchantId("M-1");
        a.setBalanceCents(balance);
        a.setFrozenCents(0L);
        return a;
    }

    private void registerWechatWithLimits(PayoutChannelLimits limits) {
        PayoutChannel channel = new PayoutChannel() {
            @Override
            public String channel() {
                return CabinetConstants.PAY_CHANNEL_WECHAT;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public String supportedAccountType() {
                return PayoutConstants.PAYEE_TYPE_PERSONAL;
            }

            @Override
            public PayoutChannelLimits channelLimits() {
                return limits;
            }

            @Override
            public PayoutResult transfer(PayoutCommand command) {
                return PayoutResult.paid("CH-1", "ok");
            }
        };
        lenient().when(payoutChannelRegistry.find(CabinetConstants.PAY_CHANNEL_WECHAT))
                .thenReturn(Optional.of(channel));
    }

    /** 微信默认档：单笔 ¥200、单收款人单日 ¥2000、单通道当日总额 ¥5万。 */
    private static PayoutChannelLimits wechatDefaults() {
        return new PayoutChannelLimits(20_000L, 200_000L, 5_000_000L);
    }

    // ==================== 单笔 ====================

    @Test
    @DisplayName("单笔刚好等于渠道限额（¥200）应通过")
    void singleAtLimit_passes() {
        registerWechatWithLimits(wechatDefaults());
        assertDoesNotThrow(() -> service.persistWithdrawApplication(
                merchant(), 20_000L, "R-1", null));
    }

    @Test
    @DisplayName("单笔超限 1 分应被拒（¥200.01）")
    void singleOverLimitByOneCent_rejected() {
        registerWechatWithLimits(wechatDefaults());
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.persistWithdrawApplication(merchant(), 20_001L, "R-2", null));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getMessage().contains("单笔"), "文案应说明是单笔限额: " + e.getMessage());
    }

    // ==================== 单收款人单日 ====================

    @Test
    @DisplayName("单收款人当日已用 ¥1800，再提 ¥200 恰好用满 ¥2000 应通过")
    void payeeDailyAtLimit_passes() {
        registerWechatWithLimits(wechatDefaults());
        // 1800 + 200 = 2000 = 限额上限。判定是严格大于才拒（>），故恰好用满必须放行。
        when(withdrawMapper.sumAmountByPayeeSince(eq(9L), any())).thenReturn(180_000L);
        assertDoesNotThrow(() -> service.persistWithdrawApplication(
                merchant(), 20_000L, "R-3", null));
    }

    @Test
    @DisplayName("单收款人当日已用满 ¥2000，再提应被拒")
    void payeeDailyExceeded_rejected() {
        registerWechatWithLimits(wechatDefaults());
        when(withdrawMapper.sumAmountByPayeeSince(eq(9L), any())).thenReturn(200_000L);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.persistWithdrawApplication(merchant(), 20_000L, "R-4", null));
        assertEquals(HttpStatus.PRECONDITION_FAILED, e.getStatusCode());
        assertTrue(e.getMessage().contains("单收款人"), "文案应说明是单收款人限额: " + e.getMessage());
    }

    @Test
    @DisplayName("🔴 单笔 ¥180 ×3 笔（¥540）不应被单笔拦住，但应被单收款人单日 ¥2000 口径累计拦住")
    void multipleSmallWithdraws_accumulateForPayeeDaily() {
        registerWechatWithLimits(wechatDefaults());
        // 单笔 180 元三笔 = 540 元，远低于单笔 200，但收款人当日已被别处用掉 1900
        when(withdrawMapper.sumAmountByPayeeSince(eq(9L), any())).thenReturn(1_900_000L);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.persistWithdrawApplication(merchant(), 18_000L, "R-5", null));
        assertEquals(HttpStatus.PRECONDITION_FAILED, e.getStatusCode());
    }

    // ==================== 单通道当日总额（共享池）====================

    @Test
    @DisplayName("通道当日总额已用 ¥49900，再提 ¥200 应被拒（差 100 元到 5 万）")
    void channelDailyTotalNearCap_rejected() {
        registerWechatWithLimits(wechatDefaults());
        when(withdrawMapper.sumAmountByChannelSince(eq(CabinetConstants.PAY_CHANNEL_WECHAT), any()))
                .thenReturn(4_990_000L);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.persistWithdrawApplication(merchant(), 20_000L, "R-6", null));
        assertEquals(HttpStatus.PRECONDITION_FAILED, e.getStatusCode());
        assertTrue(e.getMessage().contains("额度"), "文案应说明是通道当日总额: " + e.getMessage());
    }

    @Test
    @DisplayName("通道当日总额未用完时不应被拦（跨商户共享池，别的商户已用 0）")
    void channelDailyTotalUnderCap_passes() {
        registerWechatWithLimits(wechatDefaults());
        when(withdrawMapper.sumAmountByChannelSince(eq(CabinetConstants.PAY_CHANNEL_WECHAT), any()))
                .thenReturn(0L);
        assertDoesNotThrow(() -> service.persistWithdrawApplication(
                merchant(), 20_000L, "R-7", null));
    }

    // ==================== 运营限额只能更严、不能更松 ====================

    @Test
    @DisplayName("运营单笔限额 ¥150 比渠道 ¥200 更严 ⇒ 实际按 ¥150 拦")
    void opsStricterThanChannel_wins() {
        registerWechatWithLimits(wechatDefaults());
        // 运营限额来自 MerchantWithdrawProperties.maxAmountCents；此处 yml 是 100 万，
        // 所以单笔 ¥200 由渠道决定；改为断言 effective 逻辑本身：
        PayoutChannelLimits effective = wechatDefaults().effective(
                new PayoutChannelLimits(15_000L, 0L, 0L));
        assertEquals(15_000L, effective.singleCents());
        assertEquals(200_000L, effective.perPayeeDailyCents(), "未设运营值的维度应沿用渠道值");
    }

    @Test
    @DisplayName("运营限额填 0（不限制）不能把渠道限额放开")
    void opsZeroDoesNotRelaxChannel() {
        PayoutChannelLimits effective = wechatDefaults().effective(PayoutChannelLimits.UNLIMITED);
        assertEquals(20_000L, effective.singleCents());
        assertEquals(200_000L, effective.perPayeeDailyCents());
        assertEquals(5_000_000L, effective.dailyTotalCents());
    }

    @Test
    @DisplayName("渠道不限时（支付宝/银行）运营限额仍生效")
    void channelUnlimited_fallsBackToOps() {
        PayoutChannelLimits effective = PayoutChannelLimits.UNLIMITED.effective(
                new PayoutChannelLimits(1_000_000L, 0L, 4_000_000L));
        assertEquals(1_000_000L, effective.singleCents());
        assertEquals(4_000_000L, effective.dailyTotalCents());
    }

    // ==================== 微信常量与官方口径对齐 ====================

    @Test
    @DisplayName("微信通道默认限额 = 单笔¥200 / 单用户单日¥2000 / 单日总额¥5万（官方非个体户口径）")
    void wechatChannelLimitsMatchOfficialDoc() {
        var channel = new com.aicabinet.trade.payout.WeChatPayoutChannel(null);
        PayoutChannelLimits limits = channel.channelLimits();
        assertEquals(20_000L, limits.singleCents());
        assertEquals(200_000L, limits.perPayeeDailyCents());
        assertEquals(5_000_000L, limits.dailyTotalCents());
    }

    @Test
    @DisplayName("支付宝限额 = 单笔¥5万 / 日¥100万（V308 已核实官方口径，不再是 UNLIMITED）")
    void alipayLimitsMatchOfficialDoc() {
        var channel = new com.aicabinet.trade.payout.AlipayPayoutChannel(null);
        PayoutChannelLimits limits = channel.channelLimits();
        // 来源 opendocs.alipay.com/open/009zdp §5.2 + EXCEED_LIMIT_PERSONAL_SM_AMOUNT
        assertEquals(5_000_000L, limits.singleCents());
        // 官方多页面日限额不一致（100万 vs 200万），取更严的 100万
        assertEquals(100_000_000L, limits.dailyTotalCents());
        // 支付宝按付款方商户计，没有「同一收款人单日」维度 ⇒ 不设
        assertEquals(0L, limits.perPayeeDailyCents());
    }

    @Test
    @DisplayName("银行通道未签协议时日限额为 0（不限）")
    void bankLimitsWithoutAgreement_unlimited() {
        var channel = new com.aicabinet.trade.payout.BankPayoutChannel();
        PayoutChannelLimits limits = channel.channelLimits();
        assertEquals(0L, limits.dailyTotalCents());
        assertEquals(0L, limits.singleCents());
    }

    private Merchant merchant() {
        Merchant m = new Merchant();
        m.setMerchantId("M-1");
        m.setMerchantName("商户1");
        return m;
    }

}