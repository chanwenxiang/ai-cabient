package com.aicabinet.trade.service;

import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.WeChatPayClient;
import com.aicabinet.trade.support.ApiMessages;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F6「结算页支付方式选择」：{@link OrderPaymentService#chargeOrder(CabinetOrder, String)} 的显式渠道语义。
 *
 * <p>核心判据（与自动决策**相反**，必须逐条锁死）：
 * <ul>
 *   <li>显式选了免密但渠道未就绪 ⇒ <b>412 且不得回落余额</b>（背离用户意图的资金动作比失败更糟）；</li>
 *   <li>显式选了免密 ⇒ 只能走 {@code chargeExplicit}，绝不能走带偏好/入口解析的 {@code charge}；</li>
 *   <li>用户偏好渠道**不得**覆盖显式选择（本用例把偏好设成 BALANCE 再选 WECHAT）；</li>
 *   <li>幂等键**不含渠道** ⇒ 换渠道重放不能二次扣款。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderPaymentSelectedChannelTest {

    private static final long CONSUMER_ID = 10001L;

    @Mock UserInfoMapper userInfoRepository;
    @Mock PayScoreService payScoreService;
    @Mock WeChatPayClient weChatPayClient;
    @Mock AlipayPayClient alipayPayClient;
    @Mock PaymentOperationMapper paymentOperationRepository;
    @Mock CabinetOrderMapper cabinetOrderRepository;
    @Mock DistributedLockService distributedLockService;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock BalanceLedgerService balanceLedgerService;
    @Mock MemberService memberService;

    private OrderPaymentService service;

    /** balanceOnly = false：允许渠道扣款（显式选择免密的用例必须用它）。 */
    private void newService(boolean balanceOnly) {
        service = new OrderPaymentService(
                userInfoRepository,
                payScoreService,
                weChatPayClient,
                alipayPayClient,
                new WeChatPayProperties(false, "", "", "", "", "", "", "", true),
                new SecurityProperties(true),
                paymentOperationRepository,
                cabinetOrderRepository,
                distributedLockService,
                balanceLedgerService,
                new CheckoutProperties(balanceOnly, 2000),
                sessionRepository,
                null,
                null,
                null,
                null,
                null,
                memberService,
                null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @BeforeEach
    void setUp() {
        newService(false);
    }

    // ---------- 显式选余额 ----------

    @Test
    void explicitBalance_debitsBalanceAndNeverTouchesChannel() {
        CabinetOrder order = order("O-SEL-BAL", 500);
        stubLockAndOrder(order);
        when(userInfoRepository.findById(CONSUMER_ID)).thenReturn(Optional.of(user()));
        PaymentOperation ledger = new PaymentOperation();
        ledger.setOperationId("OP-SEL-BAL");
        ledger.setBalanceBeforeCents(9000);
        ledger.setBalanceAfterCents(8500);
        when(balanceLedgerService.change(eq(CONSUMER_ID), eq(-500), eq("CHARGE"),
                eq("O-SEL-BAL"), eq("CHARGE:O-SEL-BAL:500"), anyString())).thenReturn(ledger);

        service.chargeOrder(order, "BALANCE");

        assertEquals("BALANCE", order.getPayChannel());
        assertEquals("OP-SEL-BAL", order.getPaymentOperationId());
        assertEquals(8500, order.getBalanceAfterCents());
        verifyNoInteractions(payScoreService);
    }

    /** 渠道选择开关的入口值来自请求体；空白/纯空格必须等价于「未传」＝走自动决策，而不是当成非法渠道 412。 */
    @Test
    void blankRequestedChannel_fallsBackToAutoDecisionNotRejection() {
        newService(true);
        CabinetOrder order = order("O-SEL-BLANK", 500);
        stubLockAndOrder(order);
        when(userInfoRepository.findById(CONSUMER_ID)).thenReturn(Optional.of(user()));
        PaymentOperation ledger = new PaymentOperation();
        ledger.setOperationId("OP-SEL-BLANK");
        when(balanceLedgerService.change(anyLong(), anyInt(), eq("CHARGE"),
                eq("O-SEL-BLANK"), eq("CHARGE:O-SEL-BLANK:500"), anyString())).thenReturn(ledger);

        service.chargeOrder(order, "   ");

        assertEquals("BALANCE", order.getPayChannel());
        verifyNoInteractions(payScoreService);
    }

    // ---------- 显式选免密：未就绪必须 412，且不得碰余额 ----------

    @Test
    void explicitWeChatWithoutContract_rejects412WithoutSilentBalanceDowngrade() {
        CabinetOrder order = order("O-SEL-WX-BAD", 500);
        stubLockAndOrder(order);
        UserInfo user = user();
        user.setPayscoreEnabled(false);
        when(userInfoRepository.findById(CONSUMER_ID)).thenReturn(Optional.of(user));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.chargeOrder(order, "WECHAT"));

        assertEquals(HttpStatus.PRECONDITION_FAILED, ex.getStatusCode());
        assertEquals(ApiMessages.PAY_CHANNEL_NOT_READY, ex.getReason());
        assertNothingCharged();
    }

    /** 免密已开启但协议号为空（签约未落库）同样不许放行。 */
    @Test
    void explicitWeChatWithEnabledFlagButBlankContract_rejects412() {
        CabinetOrder order = order("O-SEL-WX-BLANK", 500);
        stubLockAndOrder(order);
        UserInfo user = user();
        user.setPayscoreEnabled(true);
        user.setPayscoreContractId("  ");
        when(userInfoRepository.findById(CONSUMER_ID)).thenReturn(Optional.of(user));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.chargeOrder(order, "WECHAT"));

        assertEquals(HttpStatus.PRECONDITION_FAILED, ex.getStatusCode());
        assertNothingCharged();
    }

    /**
     * 支付宝「签约中」（协议号带 {@code PENDING:} 前缀）不是可用渠道 —— 此处与
     * 前端 {@code alipayAgreementEnabled} 及 {@code AccountService#setPayPreferredChannel} 同源。
     */
    @Test
    void explicitAlipayWithPendingAgreement_rejects412() {
        CabinetOrder order = order("O-SEL-ALI-PENDING", 500);
        stubLockAndOrder(order);
        UserInfo user = user();
        user.setAlipayAgreementId("PENDING:tmp-agreement");
        when(userInfoRepository.findById(CONSUMER_ID)).thenReturn(Optional.of(user));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.chargeOrder(order, "ALIPAY"));

        assertEquals(HttpStatus.PRECONDITION_FAILED, ex.getStatusCode());
        assertNothingCharged();
    }

    // ---------- 显式选免密：已就绪 ----------

    @Test
    void explicitWeChatWhenUsable_chargesThatChannelEvenIfPreferenceIsBalance() {
        CabinetOrder order = order("O-SEL-WX", 500);
        stubLockAndOrder(order);
        UserInfo user = user();
        user.setPayscoreEnabled(true);
        user.setPayscoreContractId("CONTRACT-1");
        // 用户默认偏好是余额（UserInfo 的字段默认值就是 BALANCE）——显式选择必须压过它
        user.setPayPreferredChannel("BALANCE");
        when(userInfoRepository.findById(CONSUMER_ID)).thenReturn(Optional.of(user));
        when(payScoreService.chargeExplicit(user, "O-SEL-WX", 500, "AI开门柜购物", "WECHAT"))
                .thenReturn(new PayScoreService.ChargeResult("WECHAT", "WX-TXN-1"));

        service.chargeOrder(order, "WECHAT");

        assertEquals("WECHAT", order.getPayChannel());
        assertEquals("WX-TXN-1", order.getPayTradeNo());
        verify(payScoreService).chargeExplicit(user, "O-SEL-WX", 500, "AI开门柜购物", "WECHAT");
        // 绝不走「带偏好/入口解析」的自动决策入口
        verify(payScoreService, never()).charge(any(), anyString(), anyInt(), anyString(), any());
        verifyNoInteractions(balanceLedgerService);
    }

    @Test
    void explicitAlipayWhenUsable_chargesThatChannel() {
        CabinetOrder order = order("O-SEL-ALI", 300);
        stubLockAndOrder(order);
        UserInfo user = user();
        user.setAlipayAgreementId("2026agreement-1");
        when(userInfoRepository.findById(CONSUMER_ID)).thenReturn(Optional.of(user));
        when(payScoreService.chargeExplicit(user, "O-SEL-ALI", 300, "AI开门柜购物", "ALIPAY"))
                .thenReturn(new PayScoreService.ChargeResult("ALIPAY", "ALI-TXN-1"));

        service.chargeOrder(order, "ALIPAY");

        assertEquals("ALIPAY", order.getPayChannel());
        verify(payScoreService).chargeExplicit(user, "O-SEL-ALI", 300, "AI开门柜购物", "ALIPAY");
        verifyNoInteractions(balanceLedgerService);
    }

    /** 渠道大小写/空格由服务端规范化，前端不为此负责。 */
    @Test
    void explicitChannelIsNormalisedToUpperCase() {
        CabinetOrder order = order("O-SEL-LOWER", 500);
        stubLockAndOrder(order);
        UserInfo user = user();
        user.setPayscoreEnabled(true);
        user.setPayscoreContractId("CONTRACT-1");
        when(userInfoRepository.findById(CONSUMER_ID)).thenReturn(Optional.of(user));
        when(payScoreService.chargeExplicit(user, "O-SEL-LOWER", 500, "AI开门柜购物", "WECHAT"))
                .thenReturn(new PayScoreService.ChargeResult("WECHAT", "WX-TXN-2"));

        service.chargeOrder(order, " wechat ");

        assertEquals("WECHAT", order.getPayChannel());
        verify(payScoreService).chargeExplicit(user, "O-SEL-LOWER", 500, "AI开门柜购物", "WECHAT");
    }

    // ---------- 余额专用环境 ----------

    /**
     * {@code aicabinet.checkout.balance-only=true} 的环境不接受免密渠道；
     * 同样**不静默切回余额**，而是 412 让前端提示改选。
     */
    @Test
    void explicitAlipayInBalanceOnlyEnvironment_rejects412() {
        newService(true);
        CabinetOrder order = order("O-SEL-BALONLY", 500);
        stubLockAndOrder(order);
        UserInfo user = user();
        user.setAlipayAgreementId("2026agreement-1");
        when(userInfoRepository.findById(CONSUMER_ID)).thenReturn(Optional.of(user));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.chargeOrder(order, "ALIPAY"));

        assertEquals(HttpStatus.PRECONDITION_FAILED, ex.getStatusCode());
        assertNothingCharged();
    }

    // ---------- 幂等 ----------

    /** 幂等键只由「订单 + 金额」构成：换渠道重放不能绕开幂等而二次扣款。 */
    @Test
    void idempotencyKeyDoesNotIncludeChannel() {
        CabinetOrder order = order("O-SEL-IDEM", 500);
        stubLockAndOrder(order);
        UserInfo user = user();
        user.setPayscoreEnabled(true);
        user.setPayscoreContractId("CONTRACT-1");
        when(userInfoRepository.findById(CONSUMER_ID)).thenReturn(Optional.of(user));
        when(payScoreService.chargeExplicit(user, "O-SEL-IDEM", 500, "AI开门柜购物", "WECHAT"))
                .thenReturn(new PayScoreService.ChargeResult("WECHAT", "WX-TXN-3"));

        service.chargeOrder(order, "WECHAT");

        verify(paymentOperationRepository).save(argThat(op ->
                "CHARGE:O-SEL-IDEM:500".equals(op.getIdempotencyKey())
                        && "WECHAT".equals(op.getChannel())
                        && "COMPLETED".equals(op.getStatus())));
    }

    // ---------- fixtures ----------

    /**
     * 「什么都没扣」的唯一可靠判据。
     *
     * <p>为什么不用 {@code assertNull(order.getPayChannel())}：{@link CabinetOrder#getPayChannel()} 的
     * 字段默认值**就是** {@code "BALANCE"}，因此「仍是 BALANCE」无法区分「没动过」与「被静默降级写了」
     * —— 那是无效判据。真正的语义是「零资金动作」：两个渠道入口都没被调用，且没落任何支付流水。
     */
    private void assertNothingCharged() {
        verifyNoInteractions(payScoreService);
        verifyNoInteractions(balanceLedgerService);
        verify(paymentOperationRepository, never()).save(any(PaymentOperation.class));
    }

    private void stubLockAndOrder(CabinetOrder order) {
        when(distributedLockService.tryLock(OrderPaymentService.orderPaymentLockKey(order.getOrderId()), 60L, 5L))
                .thenReturn(true);
        when(cabinetOrderRepository.findByIdForUpdate(order.getOrderId())).thenReturn(Optional.of(order));
    }

    private CabinetOrder order(String orderId, int amountCents) {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId(orderId);
        order.setUserId(CONSUMER_ID);
        order.setTotalAmountCents(amountCents);
        return order;
    }

    private UserInfo user() {
        UserInfo user = new UserInfo();
        user.setUserId(CONSUMER_ID);
        return user;
    }
}
