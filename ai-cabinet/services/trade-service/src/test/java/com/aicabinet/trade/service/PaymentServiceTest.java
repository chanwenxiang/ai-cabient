package com.aicabinet.trade.service;

import com.aicabinet.trade.config.AlipayProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.RechargeOrder;
import com.aicabinet.trade.domain.UserAccount;
import com.aicabinet.trade.payment.AlipayNotifyService;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.WeChatPayClient;
import com.aicabinet.trade.payment.WeChatPayNotifyService;
import com.aicabinet.trade.payment.WeChatPayV3Signer;
import com.aicabinet.trade.repository.RechargeOrderRepository;
import com.aicabinet.trade.repository.UserAccountRepository;
import com.aicabinet.trade.repository.UserInfoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private RechargeOrderRepository rechargeOrderRepository;
    @Mock private UserInfoRepository userInfoRepository;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private WeChatPayClient weChatPayClient;
    @Mock private WeChatPayV3Signer v3Signer;
    @Mock private WeChatPayNotifyService notifyService;
    @Mock private AlipayPayClient alipayPayClient;
    @Mock private AlipayNotifyService alipayNotifyService;

    private PaymentService paymentService;
    private WeChatPayProperties weChatPayProperties;
    private AlipayProperties alipayProperties;
    private SecurityProperties securityProperties;

    @BeforeEach
    void setUp() {
        weChatPayProperties = new WeChatPayProperties(
                false, "", "", "", "", "", "", "", true);
        alipayProperties = new AlipayProperties(false, "", "", "", "", "");
        securityProperties = new SecurityProperties(true);
        paymentService = new PaymentService(
                rechargeOrderRepository, userInfoRepository, userAccountRepository,
                weChatPayProperties, alipayProperties, securityProperties,
                weChatPayClient, v3Signer, notifyService, alipayPayClient, alipayNotifyService);
    }

    @Test
    void cancelRecharge_mockMode_marksCancelled() {
        RechargeOrder order = pendingOrder("R001", 10001L);
        when(rechargeOrderRepository.findById("R001")).thenReturn(Optional.of(order));

        var dto = paymentService.cancelRecharge(10001L, "R001");

        assertEquals("CANCELLED", dto.status());
        assertEquals("CANCELLED", order.getStatus());
        verify(rechargeOrderRepository).save(order);
        verifyNoInteractions(weChatPayClient);
    }

    @Test
    void cancelRecharge_wrongUser_forbidden() {
        when(rechargeOrderRepository.findById("R001")).thenReturn(Optional.of(pendingOrder("R001", 10001L)));

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

        when(rechargeOrderRepository.findById("R002")).thenReturn(Optional.of(order));
        when(userAccountRepository.findById(10001L)).thenReturn(Optional.of(account));

        var dto = paymentService.refundRecharge("R002", "test refund");

        assertEquals("REFUNDED", dto.status());
        assertEquals(500, account.getBalanceCents());
        verify(rechargeOrderRepository).save(order);
        verify(userAccountRepository).save(account);
    }

    private static RechargeOrder pendingOrder(String id, Long userId) {
        RechargeOrder order = new RechargeOrder();
        order.setOrderId(id);
        order.setUserId(userId);
        order.setAmountCents(500);
        order.setChannel("WECHAT");
        order.setStatus("PENDING");
        return order;
    }

    private static RechargeOrder paidOrder(String id, Long userId, int amount) {
        RechargeOrder order = pendingOrder(id, userId);
        order.setAmountCents(amount);
        order.setStatus("PAID");
        return order;
    }
}
