package com.aicabinet.trade.service;

import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.payment.AlipayNotifyService;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.WeChatPayClient;
import com.aicabinet.trade.payment.WeChatPayNotifyService;
import com.aicabinet.trade.payment.WeChatPayV3Signer;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.UserAccountMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

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
    private WeChatPayProperties weChatPayProperties;
    private SecurityProperties securityProperties;

    @BeforeEach
    void setUp() {
        weChatPayProperties = new WeChatPayProperties(
                false, "", "", "", "", "", "", "", true);
        securityProperties = new SecurityProperties(true);
        lenient().when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
        paymentService = new PaymentService(
                rechargeOrderRepository, userInfoRepository, userAccountRepository,
                weChatPayProperties, securityProperties,
                weChatPayClient, v3Signer, notifyService, alipayPayClient, alipayNotifyService,
                balanceLedgerService, systemConfigService, notificationService, payScoreService,
                distributedLockService, paymentOperationRepository, opsAlertDispatcher, memberService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(paymentService, "self", paymentService);
    }

    @Test
    void cancelRecharge_mockMode_marksCancelled() {
        RechargeOrder order = pendingOrder("R001", 10001L);
        when(rechargeOrderRepository.findByIdForUpdate("R001")).thenReturn(Optional.of(order));

        var dto = paymentService.cancelRecharge(10001L, "R001");

        assertEquals("CANCELLED", dto.status());
        assertEquals("CANCELLED", order.getStatus());
        verify(rechargeOrderRepository).save(order);
        verifyNoInteractions(weChatPayClient);
    }

    @Test
    void cancelRecharge_wrongUser_forbidden() {
        when(rechargeOrderRepository.findByIdForUpdate("R001")).thenReturn(Optional.of(pendingOrder("R001", 10001L)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentService.cancelRecharge(99999L, "R001"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    // ── R20 P2-4：充值取消先查渠道实收（2026-11-12）──

    /** 渠道已实收 ⇒ 先补入账再拒绝取消（旧实现直接关单，回调会撞 409 无限重投）。 */
    @Test
    void cancelRecharge_whenChannelAlreadyPaid_creditsAndRejectsCancel() {
        useConfiguredWeChatPay();
        RechargeOrder order = pendingOrder("R-PAID", 10001L);
        when(rechargeOrderRepository.findByIdForUpdate("R-PAID")).thenReturn(Optional.of(order));
        when(weChatPayClient.queryByOutTradeNo("R-PAID")).thenReturn(weChatQuerySuccess(500, "WX-TXN-1"));
        when(distributedLockService.tryLock(PaymentService.rechargeLockKey("R-PAID"), 30L, 5L))
                .thenReturn(true);
        PaymentOperation credit = new PaymentOperation();
        credit.setOperationId("BL-CREDIT");
        when(balanceLedgerService.change(eq(10001L), eq(500), eq("RECHARGE"), eq("R-PAID"),
                anyString(), anyString())).thenReturn(credit);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentService.cancelRecharge(10001L, "R-PAID"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("PAID", order.getStatus());
        verify(balanceLedgerService).change(eq(10001L), eq(500), eq("RECHARGE"), eq("R-PAID"),
                anyString(), anyString());
        // 钱已经到账就不能再关单（关单只会制造「渠道已付、本地取消」的第二次错位）
        verify(weChatPayClient, never()).closeOrder(anyString());
    }

    /** 迟到的成功回调命中已取消单：救回来补入账 + 告警留痕（不再 409 让渠道重投到天亮）。 */
    @Test
    void doCreditRechargeAfterChannelPaid_whenCancelled_revivesAndAlerts() {
        RechargeOrder order = pendingOrder("R-REV", 10001L);
        order.setStatus("CANCELLED");
        when(rechargeOrderRepository.findByIdForUpdate("R-REV")).thenReturn(Optional.of(order));
        PaymentOperation credit = new PaymentOperation();
        credit.setOperationId("BL-REVIVE");
        when(balanceLedgerService.change(eq(10001L), eq(500), eq("RECHARGE"), eq("R-REV"),
                anyString(), anyString())).thenReturn(credit);

        paymentService.doCreditRechargeAfterChannelPaid("R-REV", 500, "WX-TXN-2", null);

        assertEquals("PAID", order.getStatus());
        assertNotNull(order.getPaidAt());
        verify(balanceLedgerService).change(eq(10001L), eq(500), eq("RECHARGE"), eq("R-REV"),
                anyString(), anyString());
        verify(opsAlertDispatcher).send(eq("RECHARGE_PAID_AFTER_CANCEL"), anyString(), anyString(), anyMap());
        verify(rechargeOrderRepository).save(order);
    }

    /** 边界：Mock 确认（非渠道口径）不得复活已取消单——只有渠道实收才允许。 */
    @Test
    void doCreditRecharge_whenCancelledWithoutChannelProof_stillRejects() {
        RechargeOrder order = pendingOrder("R-REJ", 10001L);
        order.setStatus("CANCELLED");
        when(rechargeOrderRepository.findByIdForUpdate("R-REJ")).thenReturn(Optional.of(order));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentService.doCreditRecharge("R-REJ", 500, null, null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("CANCELLED", order.getStatus());
        verify(balanceLedgerService, never()).change(anyLong(), anyInt(), anyString(), anyString(),
                anyString(), anyString());
    }

    private void useConfiguredWeChatPay() {
        weChatPayProperties = new WeChatPayProperties(
                true, "app", "mch", "notify", "v3key", "serial", "key", "cert", true);
        paymentService = new PaymentService(
                rechargeOrderRepository, userInfoRepository, userAccountRepository,
                weChatPayProperties, securityProperties,
                weChatPayClient, v3Signer, notifyService, alipayPayClient, alipayNotifyService,
                balanceLedgerService, systemConfigService, notificationService, payScoreService,
                distributedLockService, paymentOperationRepository, opsAlertDispatcher, memberService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(paymentService, "self", paymentService);
    }

    private static ObjectNode weChatQuerySuccess(int totalCents, String txnId) {
        ObjectNode remote = new ObjectMapper().createObjectNode();
        remote.put("trade_state", "SUCCESS");
        remote.put("transaction_id", txnId);
        remote.putObject("amount").put("total", totalCents);
        return remote;
    }

    @Test
    void refundRecharge_mockMode_debitsBalance() {
        RechargeOrder order = paidOrder("R002", 10001L, 500);
        UserAccount account = new UserAccount();
        account.setUserId(10001L);
        account.setBalanceCents(1000);

        when(rechargeOrderRepository.findByIdForUpdate("R002")).thenReturn(Optional.of(order));
        when(userAccountRepository.findByIdForUpdate(10001L)).thenReturn(Optional.of(account));
        when(paymentOperationRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        PaymentOperation operation = new PaymentOperation();
        operation.setOperationId("BL-REFUND");
        when(balanceLedgerService.change(10001L, -500, "RECHARGE_REFUND", "R002",
                "recharge-refund:R002", "test refund")).thenReturn(operation);

        var dto = paymentService.refundRecharge("R002", "test refund");

        assertEquals("REFUNDED", dto.status());
        verify(rechargeOrderRepository).save(order);
        verify(balanceLedgerService).change(10001L, -500, "RECHARGE_REFUND", "R002",
                "recharge-refund:R002", "test refund");
        var opCaptor = org.mockito.ArgumentCaptor.forClass(PaymentOperation.class);
        verify(paymentOperationRepository).save(opCaptor.capture());
        PaymentOperation channelOp = opCaptor.getValue();
        assertNull(channelOp.getOrderId());
        assertEquals("RECHARGE_REFUND", channelOp.getOperationType());
        assertTrue(channelOp.getReason().contains("R002"));
    }

    /**
     * R19-2（2026-11-12）：充 100 送 20、已消费 30（余额 90）时退款 —— 只退 70 且赠送作废。
     *
     * <p>旧实现只扣订单本金 100：余额 90 不够 100 时直接拒绝（用户投诉），
     * 若余额充足则退 100 + 送 20 留在余额里 ⇒ 可循环铸币。
     */
    @Test
    void refundRecharge_withBonus_refundsRemainingPrincipalAndRevokesBonus() {
        RechargeOrder order = paidOrder("R010", 10001L, 10000); // 充 100.00
        UserAccount account = new UserAccount();
        account.setUserId(10001L);
        account.setBalanceCents(9000); // 花掉 30 ⇒ 余额 90（其中赠送 20）

        PaymentOperation bonusOp = new PaymentOperation();
        bonusOp.setOperationId("BL-BONUS");
        bonusOp.setAmountCents(2000); // 送 20

        when(rechargeOrderRepository.findByIdForUpdate("R010")).thenReturn(Optional.of(order));
        when(userAccountRepository.findByIdForUpdate(10001L)).thenReturn(Optional.of(account));
        when(paymentOperationRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(paymentOperationRepository.findByIdempotencyKey("recharge-bonus:R010"))
                .thenReturn(Optional.of(bonusOp));

        PaymentOperation op = new PaymentOperation();
        op.setOperationId("BL-REFUND-R010");
        when(balanceLedgerService.change(any(), anyInt(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(op);

        var dto = paymentService.refundRecharge("R010", "test refund");

        assertEquals("REFUNDED", dto.status(), "部分退款也要结案，不能留在 PAID");
        // 只退本金余额 70（100 - 已消费 30）
        verify(balanceLedgerService).change(10001L, -7000, "RECHARGE_REFUND", "R010",
                "recharge-refund:R010", "test refund");
        // 赠送 20 同时作废（否则用户可以「退款拿现金 + 留赠送」）
        verify(balanceLedgerService).change(10001L, -2000, "RECHARGE_BONUS_REVOKE", "R010",
                "recharge-bonus-revoke:R010", "退款作废充值赠送");
        assertEquals(7000, order.getRefundedCents(), "refunded_cents 记真实退回额，不是订单额");
    }

    /**
     * R20-P2-8 钉测（2026-11-12）：已部分退过的充值单再走「整单退」，只能退**剩余面额**。
     *
     * <p>修前：{@code refundCents = min(面额, 余额−赠送)} 完全不看 {@code refunded_cents}
     * ⇒ 面额 100 已退 30、余额还剩 100（钱没花）时再退 100，累计退 130 > 面额。
     * {@code prepareChannelPartialRefund} 另有一套「可退 = 面额 − 已退」的口径，两套并存即超退。</p>
     */
    @Test
    void refundRecharge_afterPartialRefund_onlyRefundsRemainingAmount() {
        RechargeOrder order = paidOrder("R-PART", 10001L, 10000);
        order.setRefundedCents(3000); // 已部分退 30，单据仍是 PAID
        UserAccount account = new UserAccount();
        account.setUserId(10001L);
        account.setBalanceCents(10000); // 钱没花，余额仍是全额

        when(rechargeOrderRepository.findByIdForUpdate("R-PART")).thenReturn(Optional.of(order));
        when(userAccountRepository.findByIdForUpdate(10001L)).thenReturn(Optional.of(account));
        when(paymentOperationRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        PaymentOperation op = new PaymentOperation();
        op.setOperationId("BL-REFUND-R-PART");
        when(balanceLedgerService.change(any(), anyInt(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(op);

        paymentService.refundRecharge("R-PART", "test remainder");

        verify(balanceLedgerService).change(10001L, -7000, "RECHARGE_REFUND", "R-PART",
                "recharge-refund:R-PART", "test remainder");
        assertEquals(10000, order.getRefundedCents(), "累计退款不得超过面额");
    }

    /** 已退满（面额 = 已退）⇒ 直接拒绝，不得再发渠道退款。 */
    @Test
    void refundRecharge_alreadyFullyRefunded_rejects() {
        RechargeOrder order = paidOrder("R-FULL", 10001L, 5000);
        order.setRefundedCents(5000);
        UserAccount account = new UserAccount();
        account.setUserId(10001L);
        account.setBalanceCents(5000);

        when(rechargeOrderRepository.findByIdForUpdate("R-FULL")).thenReturn(Optional.of(order));
        when(userAccountRepository.findByIdForUpdate(10001L)).thenReturn(Optional.of(account));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentService.refundRecharge("R-FULL", "again"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(balanceLedgerService, never()).change(any(), anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    /** 本金已消费完（余额里只剩赠送）⇒ 拒绝退款，不能把赠送折现。 */
    @Test
    void refundRecharge_principalExhausted_rejects() {
        RechargeOrder order = paidOrder("R011", 10001L, 10000);
        UserAccount account = new UserAccount();
        account.setUserId(10001L);
        account.setBalanceCents(1500); // 只剩 15，且赠送 20 已被消费掉一部分

        PaymentOperation bonusOp = new PaymentOperation();
        bonusOp.setAmountCents(2000);

        when(rechargeOrderRepository.findByIdForUpdate("R011")).thenReturn(Optional.of(order));
        when(userAccountRepository.findByIdForUpdate(10001L)).thenReturn(Optional.of(account));
        when(paymentOperationRepository.findByIdempotencyKey("recharge-bonus:R011"))
                .thenReturn(Optional.of(bonusOp));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentService.refundRecharge("R011", "test refund"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(balanceLedgerService, never()).change(any(), anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void confirmRechargeMock_isIdempotentAndUsesLedger() {
        RechargeOrder order = pendingOrder("R003", 10001L);
        PaymentOperation operation = new PaymentOperation();
        operation.setOperationId("BL001");
        when(rechargeOrderRepository.findByIdForUpdate("R003")).thenReturn(Optional.of(order));
        when(balanceLedgerService.change(10001L, 500, "RECHARGE", "R003",
                "recharge-credit:R003", "充值到账（灰度环境测试余额）")).thenReturn(operation);

        var first = paymentService.confirmRechargeMock(10001L, "R003");
        var second = paymentService.confirmRechargeMock(10001L, "R003");

        assertEquals("PAID", first.status());
        assertEquals("PAID", second.status());
        assertEquals("BL001", order.getPaymentOperationId());
        verify(balanceLedgerService, times(1)).change(anyLong(), anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void handleWeChatNotify_forgedSignature_doesNotCredit() {
        when(notifyService.parseAndVerify(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException(com.aicabinet.trade.support.ApiMessages.INVALID_WECHAT_NOTIFY));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> paymentService.handleWeChatNotify("{}", "1", "n", "bad-sig", "serial"));
        assertEquals(com.aicabinet.trade.support.ApiMessages.INVALID_WECHAT_NOTIFY, ex.getMessage());

        verifyNoInteractions(rechargeOrderRepository, balanceLedgerService);
    }

    /** M8: nonce 重放在 parse 层拒绝后，不入账。 */
    @Test
    void handleWeChatNotify_nonceReplay_doesNotCredit() {
        when(notifyService.parseAndVerify(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException(com.aicabinet.trade.support.ApiMessages.WECHAT_NOTIFY_REPLAY));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> paymentService.handleWeChatNotify("{}", "1", "same-nonce", "sig", "serial"));
        assertEquals(com.aicabinet.trade.support.ApiMessages.WECHAT_NOTIFY_REPLAY, ex.getMessage());

        verifyNoInteractions(rechargeOrderRepository, balanceLedgerService);
    }

    @Test
    void autoCancelExpiredPending_cancelsOldPendingOrders() {
        RechargeOrder old = pendingOrder("R-OLD", 10001L);
        when(systemConfigService.getInt(SystemConfigService.RECHARGE_AUTO_CANCEL_MINUTES, 30)).thenReturn(30);
        when(rechargeOrderRepository.findByStatusAndCreatedAtBefore(eq("PENDING"), any()))
                .thenReturn(java.util.List.of(old));
        when(rechargeOrderRepository.findByIdForUpdate("R-OLD")).thenReturn(Optional.of(old));

        int n = paymentService.autoCancelExpiredPending();

        assertEquals(1, n);
        assertEquals("CANCELLED", old.getStatus());
        verify(rechargeOrderRepository).save(old);
    }

    @Test
    void refundRechargeChannelPartial_wechatConfiguredWithoutTxnId_rejects() {
        weChatPayProperties = new WeChatPayProperties(
                true, "app", "mch", "notify", "v3key", "serial", "key", "cert", true);
        securityProperties = new SecurityProperties(false);
        paymentService = new PaymentService(
                rechargeOrderRepository, userInfoRepository, userAccountRepository,
                weChatPayProperties, securityProperties,
                weChatPayClient, v3Signer, notifyService, alipayPayClient, alipayNotifyService,
                balanceLedgerService, systemConfigService, notificationService, payScoreService,
                distributedLockService, paymentOperationRepository, opsAlertDispatcher, memberService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(paymentService, "self", paymentService);

        RechargeOrder order = paidOrder("R-WX", 10001L, 500);
        order.setChannel("WECHAT");
        order.setWxTransactionId(null);
        when(rechargeOrderRepository.findByIdForUpdate("R-WX")).thenReturn(Optional.of(order));
        when(paymentOperationRepository.findRechargeCreditGatewayTradeNo("R-WX")).thenReturn(Optional.empty());
        ObjectNode notPaid = new ObjectMapper().createObjectNode();
        notPaid.put("trade_state", "NOTPAY");
        when(weChatPayClient.queryByOutTradeNo("R-WX")).thenReturn(notPaid);

        assertThrows(ResponseStatusException.class,
                () -> paymentService.refundRechargeChannelPartial("R-WX", 200, "test", "RF-TEST-WX"));

        verify(weChatPayClient).queryByOutTradeNo("R-WX");
        verify(weChatPayClient, never()).createRefund(anyString(), anyString(), anyInt(), anyInt(), anyString());
    }

    @Test
    void refundRechargeChannelPartial_alipayConfiguredWithoutTradeNo_rejects() {
        paymentService = new PaymentService(
                rechargeOrderRepository, userInfoRepository, userAccountRepository,
                new WeChatPayProperties(false, "", "", "", "", "", "", "", true),
                new SecurityProperties(false),
                weChatPayClient, v3Signer, notifyService, alipayPayClient, alipayNotifyService,
                balanceLedgerService, systemConfigService, notificationService, payScoreService,
                distributedLockService, paymentOperationRepository, opsAlertDispatcher, memberService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(paymentService, "self", paymentService);
        when(alipayPayClient.isConfigured()).thenReturn(true);

        RechargeOrder order = paidOrder("R-ALI", 10001L, 500);
        order.setChannel("ALIPAY");
        order.setAlipayTradeNo(null);
        when(rechargeOrderRepository.findByIdForUpdate("R-ALI")).thenReturn(Optional.of(order));
        when(paymentOperationRepository.findRechargeCreditGatewayTradeNo("R-ALI")).thenReturn(Optional.empty());
        ObjectNode closed = new ObjectMapper().createObjectNode();
        closed.put("trade_status", "TRADE_CLOSED");
        when(alipayPayClient.queryByOutTradeNo("R-ALI")).thenReturn(closed);

        assertThrows(ResponseStatusException.class,
                () -> paymentService.refundRechargeChannelPartial("R-ALI", 200, "test", "RF-TEST-ALI"));

        verify(alipayPayClient, never()).refund(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void refundRechargeChannelPartial_wechatConfigured_backfillsTxnFromCreditOperation() {
        weChatPayProperties = new WeChatPayProperties(
                true, "app", "mch", "notify", "v3key", "serial", "key", "cert", true);
        securityProperties = new SecurityProperties(false);
        paymentService = new PaymentService(
                rechargeOrderRepository, userInfoRepository, userAccountRepository,
                weChatPayProperties, securityProperties,
                weChatPayClient, v3Signer, notifyService, alipayPayClient, alipayNotifyService,
                balanceLedgerService, systemConfigService, notificationService, payScoreService,
                distributedLockService, paymentOperationRepository, opsAlertDispatcher, memberService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(paymentService, "self", paymentService);

        RechargeOrder order = paidOrder("R-WX-BF", 10001L, 500);
        order.setChannel("WECHAT");
        order.setWxTransactionId(null);
        when(rechargeOrderRepository.findByIdForUpdate("R-WX-BF")).thenReturn(Optional.of(order));
        when(paymentOperationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(paymentOperationRepository.findRechargeCreditGatewayTradeNo("R-WX-BF"))
                .thenReturn(Optional.of("WX-RECHARGE-TXN"));

        paymentService.refundRechargeChannelPartial("R-WX-BF", 200, "test", "RF-TEST-WX-BF");

        assertEquals("WX-RECHARGE-TXN", order.getWxTransactionId());
        verify(rechargeOrderRepository, atLeastOnce()).save(order);
        verify(weChatPayClient).createRefund(eq("R-WX-BF"), anyString(), eq(200), eq(500), eq("test"));
    }

    @Test
    void refundRechargeChannelPartial_alipayConfigured_backfillsTradeNoFromGatewayQuery() throws Exception {
        paymentService = new PaymentService(
                rechargeOrderRepository, userInfoRepository, userAccountRepository,
                new WeChatPayProperties(false, "", "", "", "", "", "", "", true),
                new SecurityProperties(false),
                weChatPayClient, v3Signer, notifyService, alipayPayClient, alipayNotifyService,
                balanceLedgerService, systemConfigService, notificationService, payScoreService,
                distributedLockService, paymentOperationRepository, opsAlertDispatcher, memberService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(paymentService, "self", paymentService);
        when(alipayPayClient.isConfigured()).thenReturn(true);

        RechargeOrder order = paidOrder("R-ALI-BF", 10001L, 500);
        order.setChannel("ALIPAY");
        order.setAlipayTradeNo(null);
        when(rechargeOrderRepository.findByIdForUpdate("R-ALI-BF")).thenReturn(Optional.of(order));
        when(paymentOperationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(paymentOperationRepository.findRechargeCreditGatewayTradeNo("R-ALI-BF"))
                .thenReturn(Optional.empty());
        ObjectNode remote = new ObjectMapper().createObjectNode();
        remote.put("trade_status", "TRADE_SUCCESS");
        remote.put("trade_no", "ALI-RECHARGE-TXN");
        when(alipayPayClient.queryByOutTradeNo("R-ALI-BF")).thenReturn(remote);

        paymentService.refundRechargeChannelPartial("R-ALI-BF", 200, "test", "RF-TEST-ALI-BF");

        assertEquals("ALI-RECHARGE-TXN", order.getAlipayTradeNo());
        verify(alipayPayClient).refund(eq("R-ALI-BF"), anyString(), eq(200), eq("test"));
    }

    @Test
    void createRechargePrepay_whenIdempotencyLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                PaymentService.rechargeIdempotencyLockKey("idem-1"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentService.createRechargePrepay(10001L, "WECHAT", 500, "idem-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void cancelRecharge_whenOrderLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                PaymentService.rechargeLockKey("R-BUSY"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentService.cancelRecharge(10001L, "R-BUSY"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void cancelRecharge_whenOrderNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                PaymentService.rechargeLockKey("R-MISS"), 60L, 5L))
                .thenReturn(true);
        when(rechargeOrderRepository.findByIdForUpdate("R-MISS")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> paymentService.cancelRecharge(10001L, "R-MISS"));

        verify(distributedLockService).unlock(PaymentService.rechargeLockKey("R-MISS"));
    }

    @Test
    void confirmRechargeMock_whenOrderLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                PaymentService.rechargeLockKey("R-CONF"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentService.confirmRechargeMock(10001L, "R-CONF"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void createRechargePrepay_rejectsAboveConfiguredMax() {
        when(systemConfigService.getPositiveLimit(SystemConfigService.RECHARGE_MAX_CENTS, 500_000))
                .thenReturn(500_000);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentService.createRechargePrepay(10001L, "WECHAT", 500_001, "idem-max"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(String.valueOf(ex.getReason()).contains("5000"));
        verify(rechargeOrderRepository, never()).save(any());
    }

    // ---- F5 储值营销：充值赠送（recharge.bonus.percent，0=关闭） ----

    @Test
    void rechargeBonusCents_nonPositiveInputs_areDisabled() {
        assertEquals(0, PaymentService.rechargeBonusCents(10_000, 0));
        assertEquals(0, PaymentService.rechargeBonusCents(10_000, -5));
        assertEquals(0, PaymentService.rechargeBonusCents(0, 10));
        assertEquals(0, PaymentService.rechargeBonusCents(-1, 10));
    }

    @Test
    void rechargeBonusCents_floorsToWholeCents() {
        assertEquals(1_000, PaymentService.rechargeBonusCents(10_000, 10)); // 充 100 送 10
        assertEquals(99, PaymentService.rechargeBonusCents(999, 10));       // 99.9 -> 99
        assertEquals(33, PaymentService.rechargeBonusCents(100, 33));       // 33.0
        assertEquals(0, PaymentService.rechargeBonusCents(9, 10));          // 0.9 -> 0
    }

    @Test
    void confirmRechargeMock_whenBonusEnabled_grantsBonusLedger() {
        RechargeOrder order = pendingOrder("R-BONUS", 10001L);
        order.setAmountCents(10_000);
        when(rechargeOrderRepository.findByIdForUpdate("R-BONUS")).thenReturn(Optional.of(order));
        when(systemConfigService.getInt(SystemConfigService.RECHARGE_BONUS_PERCENT, 0)).thenReturn(10);
        PaymentOperation credit = new PaymentOperation();
        credit.setOperationId("BL-CREDIT");
        when(balanceLedgerService.change(10001L, 10_000, "RECHARGE", "R-BONUS",
                "recharge-credit:R-BONUS", "充值到账（灰度环境测试余额）")).thenReturn(credit);
        PaymentOperation bonus = new PaymentOperation();
        bonus.setOperationId("BL-BONUS");
        when(balanceLedgerService.change(10001L, 1_000, "RECHARGE_BONUS", "R-BONUS",
                "recharge-bonus:R-BONUS", "充值赠送")).thenReturn(bonus);

        var dto = paymentService.confirmRechargeMock(10001L, "R-BONUS");

        assertEquals("PAID", dto.status());
        verify(balanceLedgerService).change(10001L, 1_000, "RECHARGE_BONUS", "R-BONUS",
                "recharge-bonus:R-BONUS", "充值赠送");
    }

    @Test
    void confirmRechargeMock_whenBonusDisabled_doesNotGrantBonus() {
        RechargeOrder order = pendingOrder("R-NOBONUS", 10001L);
        order.setAmountCents(10_000);
        when(rechargeOrderRepository.findByIdForUpdate("R-NOBONUS")).thenReturn(Optional.of(order));
        when(systemConfigService.getInt(SystemConfigService.RECHARGE_BONUS_PERCENT, 0)).thenReturn(0);
        PaymentOperation credit = new PaymentOperation();
        credit.setOperationId("BL-CREDIT");
        when(balanceLedgerService.change(10001L, 10_000, "RECHARGE", "R-NOBONUS",
                "recharge-credit:R-NOBONUS", "充值到账（灰度环境测试余额）")).thenReturn(credit);

        var dto = paymentService.confirmRechargeMock(10001L, "R-NOBONUS");

        assertEquals("PAID", dto.status());
        // 关闭态：只有主充值一条流水，行为与接入前逐字节一致。
        verify(balanceLedgerService, times(1))
                .change(anyLong(), anyInt(), anyString(), anyString(), anyString(), anyString());
        verify(balanceLedgerService, never())
                .change(anyLong(), anyInt(), eq("RECHARGE_BONUS"), anyString(), anyString(), anyString());
    }

    @Test
    void confirmRechargeMock_whenBonusRoundsToZero_skipsLedgerCall() {
        RechargeOrder order = pendingOrder("R-ROUND", 10001L);
        order.setAmountCents(9); // 9 分 × 10% = 0.9 -> 0；不得落 0 元流水（change(0) 会 400）
        when(rechargeOrderRepository.findByIdForUpdate("R-ROUND")).thenReturn(Optional.of(order));
        when(systemConfigService.getInt(SystemConfigService.RECHARGE_BONUS_PERCENT, 0)).thenReturn(10);
        PaymentOperation credit = new PaymentOperation();
        credit.setOperationId("BL-CREDIT");
        when(balanceLedgerService.change(10001L, 9, "RECHARGE", "R-ROUND",
                "recharge-credit:R-ROUND", "充值到账（灰度环境测试余额）")).thenReturn(credit);

        var dto = paymentService.confirmRechargeMock(10001L, "R-ROUND");

        assertEquals("PAID", dto.status());
        verify(balanceLedgerService, never())
                .change(anyLong(), anyInt(), eq("RECHARGE_BONUS"), anyString(), anyString(), anyString());
    }

    private static RechargeOrder pendingOrder(String id, Long userId) {
        RechargeOrder order = new RechargeOrder();
        order.setOrderId(id);
        order.setUserId(userId);
        order.setAmountCents(500);
        order.setChannel("WECHAT");
        order.setStatus("PENDING");
        order.setIdempotencyKey("test:" + id);
        return order;
    }

    private static RechargeOrder paidOrder(String id, Long userId, int amount) {
        RechargeOrder order = pendingOrder(id, userId);
        order.setAmountCents(amount);
        order.setStatus("PAID");
        return order;
    }

    // ── 微信 V3 退款结果通知（2026-10-23 商户平台手工退款同步，防幽灵余额）──

    private ObjectNode refundNotify(String outTradeNo, String refundId, String outRefundNo,
                                    String status, int refundCents) {
        var mapper = new ObjectMapper();
        var node = mapper.createObjectNode();
        node.put("out_trade_no", outTradeNo);
        node.put("refund_id", refundId);
        node.put("out_refund_no", outRefundNo);
        node.put("refund_status", status);
        node.putObject("amount").put("refund", refundCents);
        return node;
    }

    @Test
    void handleWeChatRefundNotify_manualRefund_syncsBalance() {
        when(notifyService.parseAndVerify(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(refundNotify("R-MANUAL", "RF-1", "", "SUCCESS", 100));
        RechargeOrder order = paidOrder("R-MANUAL", 10001L, 100);
        order.setChannel("WECHAT");
        // outRefundNo 为空（商户平台手工退款常态）⇒ 短路不查 selectCount，直接进同步
        when(rechargeOrderRepository.findById("R-MANUAL")).thenReturn(Optional.of(order));
        when(balanceLedgerService.change(eq(10001L), eq(-100), eq("RECHARGE_REFUND"), eq("R-MANUAL"),
                eq("channel-refund-sync:RF-1"), anyString()))
                .thenReturn(new PaymentOperation());
        when(rechargeOrderRepository.findByIdForUpdate("R-MANUAL")).thenReturn(Optional.of(order));

        paymentService.handleWeChatRefundNotify("body", "ts", "nonce", "sig", "serial");

        verify(balanceLedgerService).change(eq(10001L), eq(-100), eq("RECHARGE_REFUND"), eq("R-MANUAL"),
                eq("channel-refund-sync:RF-1"), anyString());
        assertEquals("REFUNDED", order.getStatus());
        assertEquals(100, order.getRefundedCents());
    }

    @Test
    void handleWeChatRefundNotify_systemInitiated_skipsDebit() {
        when(notifyService.parseAndVerify(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(refundNotify("R-SYS", "RF-2", "RN-2", "SUCCESS", 100));
        when(paymentOperationRepository.selectCount(any())).thenReturn(1L);

        paymentService.handleWeChatRefundNotify("body", "ts", "nonce", "sig", "serial");

        verify(balanceLedgerService, never()).change(any(), anyInt(), anyString(), anyString(), anyString(), anyString());
        verify(rechargeOrderRepository, never()).findById(anyString());
    }

    @Test
    void handleWeChatRefundNotify_abnormal_sendsAlert() {
        when(notifyService.parseAndVerify(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(refundNotify("R-AB", "RF-3", "", "ABNORMAL", 100));

        paymentService.handleWeChatRefundNotify("body", "ts", "nonce", "sig", "serial");

        verify(opsAlertDispatcher).send(eq("WECHAT_REFUND_ABNORMAL"), anyString(), contains("R-AB"));
        verify(balanceLedgerService, never()).change(any(), anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void handleWeChatRefundNotify_insufficientBalance_alertsInsteadOfThrow() {
        when(notifyService.parseAndVerify(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(refundNotify("R-POOR", "RF-4", "", "SUCCESS", 100));
        RechargeOrder order = paidOrder("R-POOR", 10001L, 1000);
        order.setChannel("WECHAT");
        when(rechargeOrderRepository.findById("R-POOR")).thenReturn(Optional.of(order));
        when(balanceLedgerService.change(eq(10001L), eq(-100), eq("RECHARGE_REFUND"), eq("R-POOR"),
                eq("channel-refund-sync:RF-4"), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "余额不足"));

        assertDoesNotThrow(() ->
                paymentService.handleWeChatRefundNotify("body", "ts", "nonce", "sig", "serial"));

        verify(opsAlertDispatcher).send(eq("WECHAT_REFUND_SYNC_INSUFFICIENT_BALANCE"), anyString(),
                contains("R-POOR"), anyMap());
        verify(rechargeOrderRepository, never()).findByIdForUpdate(anyString());
    }

    /**
     * MA-088 钉测：缺 {@code refund_id} 会让幂等键塌缩成全局字面量
     * {@code channel-refund-sync:null}，且该键不含订单号 ⇒ 不同订单的第二笔退款会被
     * 静默去吞（不退余额但 {@code refunded_cents} 照常推进 = 幽灵余额窗口）。
     * 现行为是「拒绝入账 + 告警 + 仍返回 204 终止微信重投」，三件事一起钉。
     */
    @Test
    void handleWeChatRefundNotify_missingRefundId_rejectsCreditAndAlerts() {
        when(notifyService.parseAndVerify(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(refundNotify("R-NOID", null, "", "SUCCESS", 100));

        assertDoesNotThrow(() ->
                paymentService.handleWeChatRefundNotify("body", "ts", "nonce", "sig", "serial"));

        verify(opsAlertDispatcher).send(eq("WECHAT_REFUND_SYNC_MISSING_REFUND_ID"),
                anyString(), contains("R-NOID"));
        verify(balanceLedgerService, never()).change(any(), anyInt(), anyString(), anyString(), anyString(), anyString());
        verify(rechargeOrderRepository, never()).findById(anyString());
    }

    /** {@code refund_id} 是空白串（不是缺失）也必须走同一道闸——判据是 isBlank 半边。 */
    @Test
    void handleWeChatRefundNotify_blankRefundId_rejectsCreditAndAlerts() {
        when(notifyService.parseAndVerify(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(refundNotify("R-BLANK", "   ", "", "SUCCESS", 100));

        paymentService.handleWeChatRefundNotify("body", "ts", "nonce", "sig", "serial");

        verify(opsAlertDispatcher).send(eq("WECHAT_REFUND_SYNC_MISSING_REFUND_ID"),
                anyString(), contains("R-BLANK"));
        verify(balanceLedgerService, never()).change(any(), anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * P2-6 钉测（2026-11-12）：微信**重投**同一笔退款通知（同 refund_id）时，单据推进也必须幂等。
     *
     * <p>修前症状：余额侧靠账本幂等键去重（不会多扣），但 {@code finalizeChannelPartialRefund}
     * 无条件 `refundedCents += n` ⇒ 重投一次就把同一笔退款记两次、订单提前置 REFUNDED，
     * 账面比渠道多退。修后判据＝同幂等键的账本流水已存在即整段跳过。</p>
     */
    @Test
    void handleWeChatRefundNotify_duplicateDelivery_doesNotDoubleCountRefunded() {
        when(notifyService.parseAndVerify(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(refundNotify("R-DUP", "RF-DUP", "", "SUCCESS", 100));
        RechargeOrder order = paidOrder("R-DUP", 10001L, 500);
        order.setChannel("WECHAT");
        when(rechargeOrderRepository.findById("R-DUP")).thenReturn(Optional.of(order));
        // 账本里已存在这笔退款的流水 ⇒ 本次是渠道重投
        when(paymentOperationRepository.findByIdempotencyKey("channel-refund-sync:RF-DUP"))
                .thenReturn(Optional.of(new PaymentOperation()));
        // 闸生效时不会走到 finalize；此处 lenient 预置，是为了让「闸被拆掉」时红在金额断言上而不是 404
        lenient().when(rechargeOrderRepository.findByIdForUpdate("R-DUP")).thenReturn(Optional.of(order));

        paymentService.handleWeChatRefundNotify("body", "ts", "nonce", "sig", "serial");

        verify(balanceLedgerService, never())
                .change(any(), anyInt(), anyString(), anyString(), anyString(), anyString());
        assertEquals(0, order.getRefundedCents(), "重投不得二次累加 refunded_cents");
        assertEquals("PAID", order.getStatus(), "重投不得把单据推成 REFUNDED");
        verify(rechargeOrderRepository, never()).findByIdForUpdate(anyString());
    }
}
