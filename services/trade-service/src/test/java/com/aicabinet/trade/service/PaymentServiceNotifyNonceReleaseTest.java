package com.aicabinet.trade.service;

import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.AlipayNotifyService;
import com.aicabinet.trade.payment.WeChatPayClient;
import com.aicabinet.trade.payment.WeChatPayNotifyService;
import com.aicabinet.trade.payment.WeChatPayV3Signer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MA-314（R23X-03 收口，2026-11-17）：回调验签通过但**入账没成**时，必须归还 nonce/notify_id
 * 防重放槽位，否则微信 10 分钟、支付宝 24 小时内渠道每次重投都被判 REPLAY，
 * 只能等查单兜底/人工。
 *
 * <p>刻意独立成一个测试类：`PaymentServiceSilentCallbackAlertTest` 被静态门禁
 * `check-payment-callback-silent-trace.mjs` 要求「该文件每个 @Test 都必须 assertDoesNotThrow」
 * （那是在钉静默分支的 200 语义）；本类的用例恰好是**失败传播**语义（assertThrows），
 * 混在一起会把两个判据互相绑死。</p>
 *
 * <p>反向保护同样重要：**已处理但不入账**的分支（渠道不符等）必须*不*归还槽位——
 * 归还等于把同一条通知放进第二次处理。</p>
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceNotifyNonceReleaseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private RechargeOrderMapper rechargeOrderRepository;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private UserAccountMapper userAccountRepository;
    @Mock private WeChatPayClient weChatPayClient;
    @Mock private WeChatPayV3Signer v3Signer;
    @Mock private WeChatPayNotifyService notifyService;
    @Mock private AlipayNotifyService alipayNotifyService;
    @Mock private AlipayPayClient alipayPayClient;
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

    @Test
    @DisplayName("微信充值：入账被拒 ⇒ 归还 nonce，渠道重投才进得来")
    void wechatRecharge_creditRejected_releasesNonceSoChannelCanRetry() {
        stubWeChat(weChatSuccess("R-WX-RETRY", 100));
        when(rechargeOrderRepository.findById("R-WX-RETRY"))
                .thenReturn(Optional.of(order("R-WX-RETRY", "WECHAT", 100)));
        // 入账入口的充值锁忙 ⇒ 503：语义是「这次没入账」，微信会重投
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(false);

        assertThrows(ResponseStatusException.class,
                () -> paymentService.handleWeChatNotify("{}", "ts", "nonce-1", "sig", "serial"));

        // 修前形态：nonce 占位留在 Redis 10 分钟 ⇒ 这段时间每次重投都被判 REPLAY
        verify(notifyService).releaseNonce("nonce-1");
    }

    @Test
    @DisplayName("微信充值：已处理但不入账（渠道不符）⇒ 必须不归还槽位")
    void wechatRecharge_handledWithoutThrow_keepsNonceSlot() {
        stubWeChat(weChatSuccess("R-WX-KEEP", 100));
        when(rechargeOrderRepository.findById("R-WX-KEEP"))
                .thenReturn(Optional.of(order("R-WX-KEEP", "ALIPAY", 100)));

        assertDoesNotThrow(() -> paymentService.handleWeChatNotify("{}", "ts", "nonce-2", "sig", "serial"));

        verify(notifyService, never()).releaseNonce(anyString());
    }

    @Test
    @DisplayName("支付宝充值：入账被拒 ⇒ 归还 notify_id（该族占位 TTL 是 24 小时）")
    void alipayRecharge_creditRejected_releasesNotifyId() {
        Map<String, String> verified = alipaySuccess("R-ALI-RETRY", "1.00");
        when(alipayNotifyService.parseAndVerify(any())).thenReturn(verified);
        when(rechargeOrderRepository.findById("R-ALI-RETRY"))
                .thenReturn(Optional.of(order("R-ALI-RETRY", "ALIPAY", 100)));
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(false);

        assertThrows(ResponseStatusException.class, () -> paymentService.handleAlipayNotify(verified));

        verify(alipayNotifyService).assertNotifyIdOnce("NID-1");
        verify(alipayNotifyService).releaseNotifyId("NID-1");
    }

    private void stubWeChat(ObjectNode node) {
        when(notifyService.parseAndVerify(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(node);
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
