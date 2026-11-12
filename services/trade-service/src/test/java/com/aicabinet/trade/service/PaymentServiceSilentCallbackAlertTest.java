package com.aicabinet.trade.service;

import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.payment.AlipayNotifyService;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.WeChatPayClient;
import com.aicabinet.trade.payment.WeChatPayNotifyService;
import com.aicabinet.trade.payment.WeChatPayV3Signer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R20 P3-4：支付回调「静默 200」全族留痕的断言级判据。
 *
 * <p>修前形态：{@code findById(...).ifPresent(...)} 对无主订单完全静默；渠道不符、金额异常
 * 只 {@code log.warn} 后 return。渠道收到 200 即停止重投 ⇒ 钱到了没入账且无人知。
 * 修后形态：每类静默分支都经过 {@code alertSilentCallback} 留本地 log.error + 运营告警，
 * 但<b>响应语义刻意不变</b>（仍 200），且告警链路异常不得把回调变成 5xx。</p>
 *
 * <p>这 10 个用例是「这一类」而非「一处」：删掉任何一个分支的告警调用都会稳定转红。
 * 静态门禁 {@code check-payment-callback-silent-trace.mjs} 负责钉住全族收口形态与 fail-open 三要素。</p>
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceSilentCallbackAlertTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private RechargeOrderMapper rechargeOrderRepository;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private UserAccountMapper userAccountRepository;
    @Mock private WeChatPayClient weChatPayClient;
    @Mock private WeChatPayV3Signer v3Signer;
    @Mock private WeChatPayNotifyService notifyService;
    @Mock private AlipayPayClient alipayPayClient;
    @Mock private AlipayNotifyService alipayNotifyService;
    @Mock private BalanceLedgerService balanceLedgerService;
    @Mock private SystemConfigService systemConfigService;
    @Mock private NotificationService notificationService;
    @Mock private PayScoreService payScoreService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private PaymentOperationMapper paymentOperationRepository;
    @Mock private OpsAlertDispatcher opsAlertDispatcher;
    @Mock private MemberService memberService;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                rechargeOrderRepository, userInfoRepository, userAccountRepository,
                new WeChatPayProperties(false, "", "", "", "", "", "", "", true),
                new SecurityProperties(true),
                weChatPayClient, v3Signer, notifyService, alipayPayClient, alipayNotifyService,
                balanceLedgerService, systemConfigService, notificationService, payScoreService,
                distributedLockService, paymentOperationRepository, opsAlertDispatcher, memberService, null);
    }

    // ── 微信充值回调：无主 / 渠道不符 / 金额异常 ──────────────────────────

    @Test
    void wechatRecharge_orderNotFound_alertsAndDoesNotCredit() {
        stubWeChat(weChatSuccess("R-WX-NONE", 100));
        when(rechargeOrderRepository.findById("R-WX-NONE")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> paymentService.handleWeChatNotify("{}", "ts", "n", "sig", "serial"));

        verifySilentAlert("WECHAT_RECHARGE_NOTIFY_ORDER_NOT_FOUND", "R-WX-NONE");
    }

    @Test
    void wechatRecharge_channelMismatch_alertsAndDoesNotCredit() {
        stubWeChat(weChatSuccess("R-WX-CH", 100));
        when(rechargeOrderRepository.findById("R-WX-CH"))
                .thenReturn(Optional.of(order("R-WX-CH", "ALIPAY", 100)));

        assertDoesNotThrow(() -> paymentService.handleWeChatNotify("{}", "ts", "n", "sig", "serial"));

        verifySilentAlert("WECHAT_RECHARGE_NOTIFY_CHANNEL_MISMATCH", "R-WX-CH");
    }

    @Test
    void wechatRecharge_amountInvalid_alertsAndDoesNotCredit() {
        stubWeChat(weChatSuccess("R-WX-AMT", 0));
        when(rechargeOrderRepository.findById("R-WX-AMT"))
                .thenReturn(Optional.of(order("R-WX-AMT", "WECHAT", 100)));

        assertDoesNotThrow(() -> paymentService.handleWeChatNotify("{}", "ts", "n", "sig", "serial"));

        verifySilentAlert("WECHAT_RECHARGE_NOTIFY_AMOUNT_INVALID", "R-WX-AMT");
    }

    // ── 支付宝充值回调：同族三分支 ────────────────────────────────────────

    @Test
    void alipayRecharge_orderNotFound_alertsAndDoesNotCredit() {
        stubAlipay(alipaySuccess("R-ALI-NONE", "1.00"));
        when(rechargeOrderRepository.findById("R-ALI-NONE")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> paymentService.handleAlipayNotify(alipaySuccess("R-ALI-NONE", "1.00")));

        verifySilentAlert("ALIPAY_RECHARGE_NOTIFY_ORDER_NOT_FOUND", "R-ALI-NONE");
    }

    @Test
    void alipayRecharge_channelMismatch_alertsAndDoesNotCredit() {
        stubAlipay(alipaySuccess("R-ALI-CH", "1.00"));
        when(rechargeOrderRepository.findById("R-ALI-CH"))
                .thenReturn(Optional.of(order("R-ALI-CH", "WECHAT", 100)));

        assertDoesNotThrow(() -> paymentService.handleAlipayNotify(alipaySuccess("R-ALI-CH", "1.00")));

        verifySilentAlert("ALIPAY_RECHARGE_NOTIFY_CHANNEL_MISMATCH", "R-ALI-CH");
    }

    @Test
    void alipayRecharge_amountInvalid_alertsAndDoesNotCredit() {
        Map<String, String> verified = alipaySuccess("R-ALI-AMT", "");
        stubAlipay(verified);
        when(rechargeOrderRepository.findById("R-ALI-AMT"))
                .thenReturn(Optional.of(order("R-ALI-AMT", "ALIPAY", 100)));

        assertDoesNotThrow(() -> paymentService.handleAlipayNotify(verified));

        verifySilentAlert("ALIPAY_RECHARGE_NOTIFY_AMOUNT_INVALID", "R-ALI-AMT");
    }

    // ── 微信退款回调 / 手工退款同步：同族四分支 ──────────────────────────

    @Test
    void wechatRefund_missingOutTradeNo_alertsAndDoesNotDebit() {
        stubWeChat(refundNotify(null, "RF-1", "SUCCESS", 100));

        assertDoesNotThrow(() -> paymentService.handleWeChatRefundNotify("{}", "ts", "n", "sig", "serial"));

        verifySilentAlert("WECHAT_REFUND_NOTIFY_MISSING_OUT_TRADE_NO", "RF-1");
    }

    @Test
    void wechatRefund_missingRefundAmount_alertsAndDoesNotDebit() {
        stubWeChat(refundNotify("R-WX-RF", "RF-2", "SUCCESS", 0));

        assertDoesNotThrow(() -> paymentService.handleWeChatRefundNotify("{}", "ts", "n", "sig", "serial"));

        verifySilentAlert("WECHAT_REFUND_NOTIFY_MISSING_REFUND_AMOUNT", "R-WX-RF");
    }

    @Test
    void wechatRefundSync_orderNotFound_alertsAndDoesNotDebit() {
        stubWeChat(refundNotify("R-WX-SYNC", "RF-3", "SUCCESS", 100));
        when(rechargeOrderRepository.findById("R-WX-SYNC")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> paymentService.handleWeChatRefundNotify("{}", "ts", "n", "sig", "serial"));

        verifySilentAlert("WECHAT_REFUND_SYNC_ORDER_NOT_FOUND", "R-WX-SYNC");
    }

    @Test
    void wechatRefundSync_channelMismatch_alertsAndDoesNotDebit() {
        stubWeChat(refundNotify("R-WX-SYNC2", "RF-4", "SUCCESS", 100));
        when(rechargeOrderRepository.findById("R-WX-SYNC2"))
                .thenReturn(Optional.of(order("R-WX-SYNC2", "ALIPAY", 100)));

        assertDoesNotThrow(() -> paymentService.handleWeChatRefundNotify("{}", "ts", "n", "sig", "serial"));

        verifySilentAlert("WECHAT_REFUND_SYNC_CHANNEL_MISMATCH", "R-WX-SYNC2");
    }

    // ── fail-open：告警链路自身异常不得把回调变成 5xx ─────────────────────

    @Test
    void silentCallback_alertChannelThrows_stillReturnsNormally() {
        stubWeChat(weChatSuccess("R-WX-BOOM", 100));
        when(rechargeOrderRepository.findById("R-WX-BOOM")).thenReturn(Optional.empty());
        doThrow(new RuntimeException("alert channel down"))
                .when(opsAlertDispatcher)
                .send(anyString(), anyString(), anyString(), anyMap());

        assertDoesNotThrow(() -> paymentService.handleWeChatNotify("{}", "ts", "n", "sig", "serial"));

        verify(balanceLedgerService, never())
                .change(anyLong(), anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    /** 每类分支统一断言：告警类型 + 本地可见的订单/单号 + 绝不入账。 */
    private void verifySilentAlert(String type, String traceToken) {
        verify(opsAlertDispatcher).send(eq(type), anyString(), contains(traceToken), anyMap());
        verify(balanceLedgerService, never())
                .change(anyLong(), anyInt(), anyString(), anyString(), anyString(), anyString());
        verify(paymentOperationRepository, never()).findByIdempotencyKey(anyString());
    }

    private void stubWeChat(ObjectNode node) {
        when(notifyService.parseAndVerify(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(node);
    }

    private void stubAlipay(Map<String, String> verified) {
        when(alipayNotifyService.parseAndVerify(any())).thenReturn(verified);
    }

    private static ObjectNode weChatSuccess(String outTradeNo, int totalCents) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("trade_state", "SUCCESS");
        node.put("out_trade_no", outTradeNo);
        node.putObject("amount").put("total", totalCents);
        return node;
    }

    private static Map<String, String> alipaySuccess(String outTradeNo, String totalAmount) {
        Map<String, String> params = new HashMap<>();
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("out_trade_no", outTradeNo);
        params.put("total_amount", totalAmount);
        params.put("notify_id", "NID-1");
        return params;
    }

    private static ObjectNode refundNotify(String outTradeNo, String refundId,
                                           String status, int refundCents) {
        ObjectNode node = MAPPER.createObjectNode();
        if (outTradeNo != null) {
            node.put("out_trade_no", outTradeNo);
        }
        node.put("refund_id", refundId);
        node.put("out_refund_no", "");
        node.put("refund_status", status);
        node.putObject("amount").put("refund", refundCents);
        return node;
    }

    private static RechargeOrder order(String id, String channel, int amountCents) {
        RechargeOrder order = new RechargeOrder();
        order.setOrderId(id);
        order.setUserId(10001L);
        order.setChannel(channel);
        order.setAmountCents(amountCents);
        order.setStatus("PENDING");
        return order;
    }
}
