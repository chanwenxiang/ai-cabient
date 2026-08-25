package com.aicabinet.trade.service;

import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.WeChatPayClient;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderPaymentConcurrencyTest {

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
    void refundOrder_whenLockBusy_rejectsWithConflict() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-LOCK");
        order.setUserId(10001L);
        when(distributedLockService.tryLock(
                eq(OrderPaymentService.orderPaymentLockKey("O-LOCK")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.refundOrder(order, 100, "test"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void refundOrder_acquiresLockAndRowLock() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-OK");
        order.setUserId(10001L);
        order.setPayChannel("BALANCE");
        when(distributedLockService.tryLock(
                eq(OrderPaymentService.orderPaymentLockKey("O-OK")), eq(60L), eq(5L)))
                .thenReturn(true);
        when(cabinetOrderRepository.findByIdForUpdate("O-OK")).thenReturn(Optional.of(order));
        when(paymentOperationRepository.netCompletedCents("O-OK")).thenReturn(0);

        service.refundOrder(order, 100, "test");

        verify(cabinetOrderRepository).findByIdForUpdate("O-OK");
        verify(distributedLockService).unlock(OrderPaymentService.orderPaymentLockKey("O-OK"));
    }
}
