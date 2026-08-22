package com.aicabinet.trade.service;

import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.WeChatPayClient;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPaymentIdempotencyTest {

    @Mock UserInfoMapper userInfoRepository;
    @Mock BalanceLedgerService balanceLedgerService;
    @Mock PayScoreService payScoreService;
    @Mock WeChatPayClient weChatPayClient;
    @Mock AlipayPayClient alipayPayClient;
    @Mock PaymentOperationMapper paymentOperationRepository;
    @Mock CabinetOrderMapper cabinetOrderRepository;
    @Mock DistributedLockService distributedLockService;
    @Mock com.aicabinet.trade.mapper.ShoppingSessionMapper sessionRepository;
    @Mock MemberService memberService;

    private OrderPaymentService service;

    @BeforeEach
    void setUp() {
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
                new CheckoutProperties(true, 2000),
                sessionRepository,
                null,
                memberService);
    }

    @Test
    void duplicateRefund_skipsSecondLedgerChange() {
        CabinetOrder order = order("O-REF-1", 10001L, 800, "BALANCE");
        stubOrderLock(order);
        stubNetCharged("O-REF-1", 800);
        PaymentOperation completed = new PaymentOperation();
        completed.setStatus("COMPLETED");
        when(paymentOperationRepository.findByIdempotencyKey(anyString()))
                .thenReturn(Optional.of(completed));

        service.refundOrder(order, 300, "dispute waive");
        service.refundOrder(order, 300, "dispute waive");

        verifyNoMoreInteractions(balanceLedgerService);
    }

    @Test
    void duplicateCharge_skipsSecondLedgerChange() {
        CabinetOrder order = order("O-CHG-1", 10001L, 500, "BALANCE");
        stubOrderLock(order);
        PaymentOperation completed = new PaymentOperation();
        completed.setStatus("COMPLETED");
        completed.setChannel("BALANCE");
        when(paymentOperationRepository.findByIdempotencyKey("CHARGE:O-CHG-1:500"))
                .thenReturn(Optional.of(completed));

        service.chargeOrder(order);
        service.chargeOrder(order);

        verifyNoMoreInteractions(balanceLedgerService);
    }

    @Test
    void firstRefund_writesLedgerOnce() {
        CabinetOrder order = order("O-REF-2", 10001L, 800, "BALANCE");
        stubOrderLock(order);
        stubNetCharged("O-REF-2", 800);
        when(paymentOperationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());

        service.refundOrder(order, 200, "duplicate test");

        verify(balanceLedgerService, times(1)).change(
                eq(10001L), eq(200), eq("REFUND"), eq("O-REF-2"), any(), eq("duplicate test"));
    }

    @Test
    void mockWeChatRefund_creditsWalletLedger() {
        CabinetOrder order = order("O-WX-REF", 10001L, 500, "WECHAT");
        stubOrderLock(order);
        stubNetCharged("O-WX-REF", 500);
        when(paymentOperationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());

        service.refundOrder(order, 500, "dispute waive");

        verify(balanceLedgerService, times(1)).change(
                eq(10001L), eq(500), eq("REFUND"), eq("O-WX-REF"),
                org.mockito.ArgumentMatchers.startsWith("REFUND:O-WX-REF:"),
                org.mockito.ArgumentMatchers.contains("模拟支付退回余额"));
        verify(paymentOperationRepository, times(1)).save(any(PaymentOperation.class));
    }

    @Test
    void mockAlipayRefund_creditsWalletLedger() {
        CabinetOrder order = order("O-ALI-REF", 10001L, 300, "ALIPAY");
        stubOrderLock(order);
        stubNetCharged("O-ALI-REF", 300);
        when(paymentOperationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(alipayPayClient.isConfigured()).thenReturn(false);

        service.refundOrder(order, 300, "dispute waive");

        verify(balanceLedgerService, times(1)).change(
                eq(10001L), eq(300), eq("REFUND"), eq("O-ALI-REF"),
                org.mockito.ArgumentMatchers.startsWith("REFUND:O-ALI-REF:"),
                org.mockito.ArgumentMatchers.contains("模拟支付退回余额"));
        verify(paymentOperationRepository, times(1)).save(any(PaymentOperation.class));
    }

    @Test
    void wechatRefundWithoutGatewayOrMock_rejectsInsteadOfWalletCredit() {
        service = new OrderPaymentService(
                userInfoRepository,
                payScoreService,
                weChatPayClient,
                alipayPayClient,
                new WeChatPayProperties(false, "", "", "", "", "", "", "", true),
                new SecurityProperties(false),
                paymentOperationRepository,
                cabinetOrderRepository,
                distributedLockService,
                balanceLedgerService,
                new CheckoutProperties(true, 2000),
                sessionRepository,
                null,
                memberService);
        CabinetOrder order = order("O-WX-PROD", 10001L, 500, "WECHAT");
        stubOrderLock(order);
        stubNetCharged("O-WX-PROD", 500);
        when(paymentOperationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.refundOrder(order, 500, "dispute waive"));

        verifyNoMoreInteractions(balanceLedgerService);
    }

    @Test
    void alipayRefundWithoutGatewayOrMock_rejectsInsteadOfWalletCredit() {
        service = new OrderPaymentService(
                userInfoRepository,
                payScoreService,
                weChatPayClient,
                alipayPayClient,
                new WeChatPayProperties(false, "", "", "", "", "", "", "", true),
                new SecurityProperties(false),
                paymentOperationRepository,
                cabinetOrderRepository,
                distributedLockService,
                balanceLedgerService,
                new CheckoutProperties(true, 2000),
                sessionRepository,
                null,
                memberService);
        CabinetOrder order = order("O-ALI-PROD", 10001L, 300, "ALIPAY");
        stubOrderLock(order);
        stubNetCharged("O-ALI-PROD", 300);
        when(paymentOperationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(alipayPayClient.isConfigured()).thenReturn(false);

        assertThrows(ResponseStatusException.class,
                () -> service.refundOrder(order, 300, "dispute waive"));

        verifyNoMoreInteractions(balanceLedgerService);
    }

    @Test
    void wechatRefundConfiguredWithoutPayTradeNo_rejectsInsteadOfMockCredit() {
        service = new OrderPaymentService(
                userInfoRepository,
                payScoreService,
                weChatPayClient,
                alipayPayClient,
                new WeChatPayProperties(true, "app", "mch", "notify", "v3key", "serial", "key", "cert", true),
                new SecurityProperties(true),
                paymentOperationRepository,
                cabinetOrderRepository,
                distributedLockService,
                balanceLedgerService,
                new CheckoutProperties(true, 2000),
                sessionRepository,
                null,
                memberService);
        CabinetOrder order = order("O-WX-NOTXN", 10001L, 500, "WECHAT");
        stubOrderLock(order);
        order.setPayTradeNo(null);
        stubNetCharged("O-WX-NOTXN", 500);
        when(paymentOperationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.refundOrder(order, 500, "dispute waive"));

        verifyNoMoreInteractions(balanceLedgerService);
        verifyNoMoreInteractions(weChatPayClient);
    }

    @Test
    void alipayRefundConfiguredWithoutPayTradeNo_rejectsInsteadOfMockCredit() {
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
                new CheckoutProperties(true, 2000),
                sessionRepository,
                null,
                memberService);
        when(alipayPayClient.isConfigured()).thenReturn(true);
        CabinetOrder order = order("O-ALI-NOTXN", 10001L, 300, "ALIPAY");
        stubOrderLock(order);
        order.setPayTradeNo(null);
        stubNetCharged("O-ALI-NOTXN", 300);
        when(paymentOperationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.refundOrder(order, 300, "dispute waive"));

        verify(alipayPayClient, never()).refund(anyString(), anyString(), anyInt(), anyString());
        verifyNoMoreInteractions(balanceLedgerService);
    }

    @Test
    void wechatRefundConfigured_backfillsPayTradeNoFromChargeOperation() {
        service = new OrderPaymentService(
                userInfoRepository,
                payScoreService,
                weChatPayClient,
                alipayPayClient,
                new WeChatPayProperties(true, "app", "mch", "notify", "v3key", "serial", "key", "cert", true),
                new SecurityProperties(false),
                paymentOperationRepository,
                cabinetOrderRepository,
                distributedLockService,
                balanceLedgerService,
                new CheckoutProperties(true, 2000),
                sessionRepository,
                null,
                memberService);
        CabinetOrder order = order("O-WX-BACKFILL", 10001L, 500, "WECHAT");
        stubOrderLock(order);
        order.setPayTradeNo(null);
        stubNetCharged("O-WX-BACKFILL", 500);
        when(paymentOperationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(paymentOperationRepository.findLatestGatewayTradeNoForCharge("O-WX-BACKFILL", "WECHAT"))
                .thenReturn(Optional.of("WX-LEGACY-TXN"));
        when(cabinetOrderRepository.backfillPayTradeNoIfAbsent("O-WX-BACKFILL", "WX-LEGACY-TXN")).thenReturn(1);
        PaymentOperation charge = new PaymentOperation();
        charge.setAmountCents(500);
        when(paymentOperationRepository.selectList(any())).thenReturn(List.of(charge));

        service.refundOrder(order, 500, "dispute waive");

        assertEquals("WX-LEGACY-TXN", order.getPayTradeNo());
        verify(cabinetOrderRepository).backfillPayTradeNoIfAbsent("O-WX-BACKFILL", "WX-LEGACY-TXN");
        verify(weChatPayClient, times(1)).createRefund(
                eq("O-WX-BACKFILL"), anyString(), eq(500), eq(500), anyString());
        verifyNoMoreInteractions(balanceLedgerService);
    }

    @Test
    void alipayRefundConfigured_backfillsPayTradeNoFromChargeOperation() {
        service = new OrderPaymentService(
                userInfoRepository,
                payScoreService,
                weChatPayClient,
                alipayPayClient,
                new WeChatPayProperties(false, "", "", "", "", "", "", "", true),
                new SecurityProperties(false),
                paymentOperationRepository,
                cabinetOrderRepository,
                distributedLockService,
                balanceLedgerService,
                new CheckoutProperties(true, 2000),
                sessionRepository,
                null,
                memberService);
        when(alipayPayClient.isConfigured()).thenReturn(true);
        CabinetOrder order = order("O-ALI-BACKFILL", 10001L, 300, "ALIPAY");
        stubOrderLock(order);
        order.setPayTradeNo(null);
        stubNetCharged("O-ALI-BACKFILL", 300);
        when(paymentOperationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(paymentOperationRepository.findLatestGatewayTradeNoForCharge("O-ALI-BACKFILL", "ALIPAY"))
                .thenReturn(Optional.of("ALI-LEGACY-TXN"));
        when(cabinetOrderRepository.backfillPayTradeNoIfAbsent("O-ALI-BACKFILL", "ALI-LEGACY-TXN")).thenReturn(1);

        service.refundOrder(order, 300, "dispute waive");

        assertEquals("ALI-LEGACY-TXN", order.getPayTradeNo());
        verify(cabinetOrderRepository).backfillPayTradeNoIfAbsent("O-ALI-BACKFILL", "ALI-LEGACY-TXN");
        verify(alipayPayClient, times(1)).refund(eq("O-ALI-BACKFILL"), anyString(), eq(300), anyString());
        verifyNoMoreInteractions(balanceLedgerService);
    }

    private void stubNetCharged(String orderId, int cents) {
        when(paymentOperationRepository.netCompletedCents(orderId)).thenReturn(cents);
    }

    private void stubOrderLock(CabinetOrder order) {
        when(distributedLockService.tryLock(eq(OrderPaymentService.orderPaymentLockKey(order.getOrderId())), eq(60L), eq(5L)))
                .thenReturn(true);
        when(cabinetOrderRepository.findByIdForUpdate(order.getOrderId())).thenReturn(Optional.of(order));
    }

    private CabinetOrder order(String orderId, long userId, int amountCents, String channel) {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId(orderId);
        order.setUserId(userId);
        order.setTotalAmountCents(amountCents);
        order.setPayChannel(channel);
        return order;
    }
}
