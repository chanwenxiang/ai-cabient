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
class PaymentRechargeConcurrencyTest {

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

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                rechargeOrderRepository, userInfoRepository, userAccountRepository,
                new WeChatPayProperties(false, "", "", "", "", "", "", "", true),
                new SecurityProperties(true),
                weChatPayClient, v3Signer, notifyService, alipayPayClient, alipayNotifyService,
                balanceLedgerService, systemConfigService, notificationService, payScoreService,
                distributedLockService, paymentOperationRepository);
    }

    @Test
    void createRechargePrepay_whenIdempotencyLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(PaymentService.rechargeIdempotencyLockKey("idem-1")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> paymentService.createRechargePrepay(10001L, "WECHAT", 500, "idem-1", "127.0.0.1"));

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

    private static RechargeOrder pendingOrder(String orderId, Long userId) {
        RechargeOrder order = new RechargeOrder();
        order.setOrderId(orderId);
        order.setUserId(userId);
        order.setAmountCents(500);
        order.setChannel("WECHAT");
        order.setStatus("PENDING");
        return order;
    }
}
