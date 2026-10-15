package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.payment.AlipayPayClient;
import com.aicabinet.trade.payment.WeChatPayClient;
import com.aicabinet.trade.payment.WeChatPayV3Client;
import com.aicabinet.trade.config.CheckoutProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B1/G1（2026-10-15）：渠道退款重试闭环钉住测试。
 *
 * <p>覆盖：查单成功即闭环不重发／ABNORMAL 不自动重发／无结果同号重发（渠道幂等）／
 * 分布式锁忙跳过本轮（对齐 refundOrder 范式）／耗尽置 REFUND_FAILED + 告警。
 */
@ExtendWith(MockitoExtension.class)
class OrderPaymentRefundRetryTraceTest {

    @Mock UserInfoMapper userInfoRepository;
    @Mock BalanceLedgerService balanceLedgerService;
    @Mock PayScoreService payScoreService;
    @Mock WeChatPayClient weChatPayClient;
    @Mock WeChatPayV3Client weChatPayV3Client;
    @Mock AlipayPayClient alipayPayClient;
    @Mock PaymentOperationMapper paymentOperationRepository;
    @Mock CabinetOrderMapper cabinetOrderRepository;
    @Mock DistributedLockService distributedLockService;
    @Mock OpsAlertDispatcher opsAlertDispatcher;
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
                null,
                null,
                opsAlertDispatcher,
                null,
                memberService,
                null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    private CabinetOrder order(String orderId) {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId(orderId);
        order.setUserId(10001L);
        order.setPayTradeNo("PT-" + orderId);
        order.setTotalAmountCents(800);
        order.setRefundedCents(0);
        return order;
    }

    private PaymentOperation trace(String orderId, String refundIdemKey, String channel) {
        PaymentOperation trace = new PaymentOperation();
        trace.setOperationId("OP-" + refundIdemKey);
        trace.setOrderId(orderId);
        trace.setOperationType("REFUND");
        trace.setAmountCents(800);
        trace.setChannel(channel);
        trace.setStatus("REFUND_INITIATED");
        trace.setIdempotencyKey("REFUND:INIT:" + refundIdemKey);
        trace.setGatewayTradeNo("00123456780098765432");
        return trace;
    }

    private static JsonNode json(String status) {
        return new ObjectMapper().valueToTree(Map.of("status", status));
    }

    @Test
    void retry_wechatQuerySuccess_closesWithoutResubmit() {
        PaymentOperation t = trace("O-RT-1", "rk1", "WECHAT");
        when(weChatPayClient.v3()).thenReturn(weChatPayV3Client);
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
        when(paymentOperationRepository.findByIdempotencyKey("rk1")).thenReturn(Optional.empty());
        when(paymentOperationRepository.findByIdempotencyKey("REFUND:INIT:rk1")).thenReturn(Optional.empty());
        when(cabinetOrderRepository.selectById("O-RT-1")).thenReturn(order("O-RT-1"));
        when(paymentOperationRepository.selectList(any())).thenReturn(Collections.emptyList());
        when(weChatPayV3Client.queryRefund(eq("O-RT-1"), anyString())).thenReturn(json("SUCCESS"));
        when(cabinetOrderRepository.findByIdForUpdate("O-RT-1")).thenReturn(Optional.of(order("O-RT-1")));

        boolean closed = service.retryFromInitiatedTrace(t);

        assertTrue(closed);
        verify(weChatPayClient, never()).createRefund(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
        ArgumentCaptor<PaymentOperation> saved = ArgumentCaptor.forClass(PaymentOperation.class);
        verify(paymentOperationRepository).save(saved.capture());
        assertEquals("COMPLETED", saved.getValue().getStatus());
        assertEquals("REFUND", saved.getValue().getOperationType());
    }

    @Test
    void retry_wechatQueryAbnormal_doesNotResubmit() {
        PaymentOperation t = trace("O-RT-2", "rk2", "WECHAT");
        when(weChatPayClient.v3()).thenReturn(weChatPayV3Client);
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
        when(paymentOperationRepository.findByIdempotencyKey("rk2")).thenReturn(Optional.empty());
        when(cabinetOrderRepository.selectById("O-RT-2")).thenReturn(order("O-RT-2"));
        when(paymentOperationRepository.selectList(any())).thenReturn(Collections.emptyList());
        when(weChatPayV3Client.queryRefund(eq("O-RT-2"), anyString())).thenReturn(json("ABNORMAL"));

        boolean closed = service.retryFromInitiatedTrace(t);

        // ABNORMAL 需渠道侧人工：不自动重发（双退风险），留给调度器 30 分钟后置 FAILED+告警
        assertFalse(closed);
        verify(weChatPayClient, never()).createRefund(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
        verify(paymentOperationRepository, never()).save(any(PaymentOperation.class));
    }

    @Test
    void retry_wechatNoResult_resubmitsWithSameRefundNo() {
        PaymentOperation t = trace("O-RT-3", "rk3", "WECHAT");
        when(weChatPayClient.v3()).thenReturn(weChatPayV3Client);
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
        when(paymentOperationRepository.findByIdempotencyKey("rk3")).thenReturn(Optional.empty());
        when(paymentOperationRepository.findByIdempotencyKey("REFUND:INIT:rk3")).thenReturn(Optional.empty());
        when(cabinetOrderRepository.selectById("O-RT-3")).thenReturn(order("O-RT-3"));
        when(paymentOperationRepository.selectList(any())).thenReturn(Collections.emptyList());
        when(weChatPayV3Client.queryRefund(eq("O-RT-3"), anyString())).thenReturn(json("PROCESSING"));
        when(weChatPayClient.createRefund(eq("O-RT-3"), anyString(), eq(800), eq(800), anyString()))
                .thenReturn(json("SUCCESS"));
        when(cabinetOrderRepository.findByIdForUpdate("O-RT-3")).thenReturn(Optional.of(order("O-RT-3")));

        boolean closed = service.retryFromInitiatedTrace(t);

        assertTrue(closed);
        ArgumentCaptor<String> queriedNo = ArgumentCaptor.forClass(String.class);
        verify(weChatPayV3Client).queryRefund(eq("O-RT-3"), queriedNo.capture());
        ArgumentCaptor<String> resubmittedNo = ArgumentCaptor.forClass(String.class);
        verify(weChatPayClient).createRefund(eq("O-RT-3"), resubmittedNo.capture(), eq(800), eq(800), anyString());
        // 查单与重发必须同号：out_refund_no 渠道侧幂等，重发不会双退
        assertEquals(queriedNo.getValue(), resubmittedNo.getValue());
        assertTrue(resubmittedNo.getValue().matches("\\d{20}"));
    }

    @Test
    void retry_lockBusy_skipsThisRound() {
        PaymentOperation t = trace("O-RT-4", "rk4", "WECHAT");
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(false);

        boolean closed = service.retryFromInitiatedTrace(t);

        // 锁被占 = 主链/上一轮正在处理同一订单：本轮跳过，痕迹留给下轮
        assertFalse(closed);
        verify(cabinetOrderRepository, never()).selectById(any());
        verify(weChatPayClient, never()).createRefund(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    @Test
    void markRefundFailed_setsStatusAndAlertsOps() {
        PaymentOperation t = trace("O-RT-5", "rk5", "WECHAT");
        when(paymentOperationRepository.findById("OP-rk5")).thenReturn(Optional.of(t));

        service.markRefundFailed(t, "超 30 分钟无终态");

        assertEquals("REFUND_FAILED", t.getStatus());
        verify(paymentOperationRepository).updateById(t);
        verify(opsAlertDispatcher).send(eq("REFUND_RETRY_EXHAUSTED"), eq("渠道退款重试耗尽"),
                contains("O-RT-5"));
    }
}
