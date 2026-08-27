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

    private PaymentService paymentService;
    private WeChatPayProperties weChatPayProperties;
    private SecurityProperties securityProperties;

    @BeforeEach
    void setUp() {
        weChatPayProperties = new WeChatPayProperties(
                false, "", "", "", "", "", "", "", true);
        securityProperties = new SecurityProperties(true);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        paymentService = new PaymentService(
                rechargeOrderRepository, userInfoRepository, userAccountRepository,
                weChatPayProperties, securityProperties,
                weChatPayClient, v3Signer, notifyService, alipayPayClient, alipayNotifyService,
                balanceLedgerService, systemConfigService, notificationService, payScoreService,
                distributedLockService, paymentOperationRepository);
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
        verify(paymentOperationRepository).save(any(PaymentOperation.class));
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

    @Test
    void autoCancelExpiredPending_cancelsOldPendingOrders() {
        RechargeOrder old = pendingOrder("R-OLD", 10001L);
        when(systemConfigService.getInt(SystemConfigService.RECHARGE_AUTO_CANCEL_MINUTES, 30)).thenReturn(30);
        when(rechargeOrderRepository.findByStatusAndCreatedAtBefore(eq("PENDING"), any()))
                .thenReturn(java.util.List.of(old));

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
                distributedLockService, paymentOperationRepository);

        RechargeOrder order = paidOrder("R-WX", 10001L, 500);
        order.setChannel("WECHAT");
        order.setWxTransactionId(null);
        when(rechargeOrderRepository.findByIdForUpdate("R-WX")).thenReturn(Optional.of(order));
        when(paymentOperationRepository.findRechargeCreditGatewayTradeNo("R-WX")).thenReturn(Optional.empty());
        ObjectNode notPaid = new ObjectMapper().createObjectNode();
        notPaid.put("trade_state", "NOTPAY");
        when(weChatPayClient.queryByOutTradeNo("R-WX")).thenReturn(notPaid);

        assertThrows(ResponseStatusException.class,
                () -> paymentService.refundRechargeChannelPartial("R-WX", 200, "test", null));

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
                distributedLockService, paymentOperationRepository);
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
                () -> paymentService.refundRechargeChannelPartial("R-ALI", 200, "test", null));

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
                distributedLockService, paymentOperationRepository);

        RechargeOrder order = paidOrder("R-WX-BF", 10001L, 500);
        order.setChannel("WECHAT");
        order.setWxTransactionId(null);
        when(rechargeOrderRepository.findByIdForUpdate("R-WX-BF")).thenReturn(Optional.of(order));
        when(paymentOperationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(paymentOperationRepository.findRechargeCreditGatewayTradeNo("R-WX-BF"))
                .thenReturn(Optional.of("WX-RECHARGE-TXN"));

        paymentService.refundRechargeChannelPartial("R-WX-BF", 200, "test", null);

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
                distributedLockService, paymentOperationRepository);
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

        paymentService.refundRechargeChannelPartial("R-ALI-BF", 200, "test", null);

        assertEquals("ALI-RECHARGE-TXN", order.getAlipayTradeNo());
        verify(alipayPayClient).refund(eq("R-ALI-BF"), anyString(), eq(200), eq("test"));
    }

    @Test
    void createRechargePrepay_whenIdempotencyLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(PaymentService.rechargeIdempotencyLockKey("idem-1")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentService.createRechargePrepay(10001L, "WECHAT", 500, "idem-1"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void cancelRecharge_whenOrderLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(PaymentService.rechargeLockKey("R-BUSY")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentService.cancelRecharge(10001L, "R-BUSY"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void cancelRecharge_whenOrderNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(PaymentService.rechargeLockKey("R-MISS")), eq(60L), eq(5L)))
                .thenReturn(true);
        when(rechargeOrderRepository.findByIdForUpdate("R-MISS")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> paymentService.cancelRecharge(10001L, "R-MISS"));

        verify(distributedLockService).unlock(PaymentService.rechargeLockKey("R-MISS"));
    }

    @Test
    void confirmRechargeMock_whenOrderLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(PaymentService.rechargeLockKey("R-CONF")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentService.confirmRechargeMock(10001L, "R-CONF"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
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
}
