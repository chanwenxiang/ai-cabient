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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
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
        lenient().when(paymentOperationRepository.failIfInitiated(anyString(), any())).thenReturn(1);
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
        when(paymentOperationRepository.findCompletedPaymentOpsByOrderId(anyString()))
                .thenReturn(Collections.emptyList());
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
    void retry_wechatQuerySuccess_restoresContextAndResumesDownstream() {
        PaymentOperation trace = trace("O-RT-CONTEXT", "rk-context", "WECHAT");
        RefundResumeService.OrderRefundContext context = RefundResumeService.fullContext(
                "O-RT-CONTEXT", true, 800, "原退款");
        trace.setRefundContextJson(RefundResumeService.orderContextJson(context));
        RefundResumeService refundResumeService = mock(RefundResumeService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "refundResumeService", refundResumeService);
        when(weChatPayClient.v3()).thenReturn(weChatPayV3Client);
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
        PaymentOperation completed = new PaymentOperation();
        completed.setOperationId("OP-CONTEXT-COMPLETED");
        completed.setOrderId("O-RT-CONTEXT");
        completed.setOperationType("REFUND");
        completed.setStatus("COMPLETED");
        completed.setIdempotencyKey("rk-context");
        completed.setRefundContextJson(trace.getRefundContextJson());
        when(paymentOperationRepository.findByIdempotencyKey("rk-context"))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(completed));
        when(paymentOperationRepository.findByIdempotencyKey("REFUND:INIT:rk-context")).thenReturn(Optional.empty());
        when(cabinetOrderRepository.selectById("O-RT-CONTEXT")).thenReturn(order("O-RT-CONTEXT"));
        when(paymentOperationRepository.findCompletedPaymentOpsByOrderId(anyString()))
                .thenReturn(Collections.emptyList());
        when(weChatPayV3Client.queryRefund(eq("O-RT-CONTEXT"), anyString())).thenReturn(json("SUCCESS"));
        when(cabinetOrderRepository.findByIdForUpdate("O-RT-CONTEXT")).thenReturn(Optional.of(order("O-RT-CONTEXT")));

        assertTrue(service.retryFromInitiatedTrace(trace));

        ArgumentCaptor<PaymentOperation> saved = ArgumentCaptor.forClass(PaymentOperation.class);
        verify(paymentOperationRepository).save(saved.capture());
        assertEquals(trace.getRefundContextJson(), saved.getValue().getRefundContextJson());
        verify(refundResumeService).resumeOrderRefund(any(PaymentOperation.class));
    }

    @Test
    void retry_wechatQueryAbnormal_doesNotResubmit() {
        PaymentOperation t = trace("O-RT-2", "rk2", "WECHAT");
        when(weChatPayClient.v3()).thenReturn(weChatPayV3Client);
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
        when(paymentOperationRepository.findByIdempotencyKey("rk2")).thenReturn(Optional.empty());
        when(cabinetOrderRepository.selectById("O-RT-2")).thenReturn(order("O-RT-2"));
        when(paymentOperationRepository.findCompletedPaymentOpsByOrderId(anyString()))
                .thenReturn(Collections.emptyList());
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
        when(paymentOperationRepository.findCompletedPaymentOpsByOrderId(anyString()))
                .thenReturn(Collections.emptyList());
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
        verify(paymentOperationRepository).failIfInitiated(eq("OP-rk5"), any());
        verify(opsAlertDispatcher).send(eq("REFUND_RETRY_EXHAUSTED"), eq("渠道退款重试耗尽"),
                contains("O-RT-5"));
    }

    /**
     * R20-1：微信 createRefund 返回 ABNORMAL（退款异常）时，钱没到用户手里，
     * 绝不能记 REFUND COMPLETED + 累加 refundedCents。此前的实现无条件 finalize，
     * 造成「账上已退、用户没收到」，而 WeChatRefundReconciler 只扫 PROCESSING 无人兜底。
     */
    @Test
    void finalize_abnormalStatus_recordsFailedOnly_andKeepsRefundedCents() {
        CabinetOrder o = order("O-AB-1");
        when(cabinetOrderRepository.findByIdForUpdate("O-AB-1")).thenReturn(Optional.of(o));
        when(paymentOperationRepository.findByIdempotencyKey("rk-ab")).thenReturn(Optional.empty());
        when(paymentOperationRepository.findByIdempotencyKey("rk-ab:ABNORMAL")).thenReturn(Optional.empty());

        OrderPaymentService.OrderRefundPrep prep = new OrderPaymentService.OrderRefundPrep(
                "O-AB-1", 10001L, 800, 800, "退款", "rk-ab", "WECHAT", "PT-O-AB-1", 800, false, true);

        boolean recorded = service.finalizeLiveOrderRefund(prep, "ABNORMAL");

        assertFalse(recorded, "ABNORMAL 必须返回 false，调用方才会中止本地推进");
        ArgumentCaptor<PaymentOperation> saved = ArgumentCaptor.forClass(PaymentOperation.class);
        verify(paymentOperationRepository).save(saved.capture());
        assertEquals("REFUND_FAILED", saved.getValue().getStatus(), "只许记 REFUND_FAILED");
        assertEquals("REFUND", saved.getValue().getOperationType());
        assertEquals(0, o.getRefundedCents(), "已退金额不得累加——钱没退成功");
        verify(cabinetOrderRepository, never()).updateById(any());
    }

    @Test
    void finalize_processingStatus_doesNotAdvanceLocalRefundOrDownstream() {
        CabinetOrder o = order("O-PROC-1");
        when(cabinetOrderRepository.findByIdForUpdate("O-PROC-1")).thenReturn(Optional.of(o));
        when(paymentOperationRepository.findByIdempotencyKey("rk-proc")).thenReturn(Optional.empty());

        OrderPaymentService.OrderRefundPrep prep = new OrderPaymentService.OrderRefundPrep(
                "O-PROC-1", 10001L, 800, 800, "退款", "rk-proc", "WECHAT", "PT-O-PROC-1", 800, false, true);

        assertFalse(service.finalizeLiveOrderRefund(prep, "PROCESSING"));
        ArgumentCaptor<PaymentOperation> saved = ArgumentCaptor.forClass(PaymentOperation.class);
        verify(paymentOperationRepository).save(saved.capture());
        assertEquals("REFUND_PROCESSING", saved.getValue().getStatus());
        assertEquals(0, o.getRefundedCents());
        verify(cabinetOrderRepository, never()).updateById(any());
        verify(memberService, never()).clawbackPointsOnRefund(anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    void confirmProcessing_successAdvancesLocalRefundExactlyOnce() {
        CabinetOrder o = order("O-PROC-2");
        PaymentOperation pending = new PaymentOperation();
        pending.setOperationId("OP-PROC-2");
        pending.setOrderId("O-PROC-2");
        pending.setUserId(10001L);
        pending.setOperationType("REFUND");
        pending.setAmountCents(800);
        pending.setChannel("WECHAT");
        pending.setStatus("REFUND_PROCESSING");
        pending.setIdempotencyKey("rk-proc-2");
        pending.setGatewayTradeNo("00123456780098765432");
        pending.setReason("退款 [refund:PROCESSING]");
        when(paymentOperationRepository.finishProcessingRefund(
                "OP-PROC-2", "退款 [refund:PROCESSING]", "退款 [refund:SUCCESS]", "COMPLETED"))
                .thenReturn(1);
        when(cabinetOrderRepository.findByIdForUpdate("O-PROC-2")).thenReturn(Optional.of(o));
        when(paymentOperationRepository.findByIdempotencyKey("rk-proc-2:BALANCE"))
                .thenReturn(Optional.empty());

        assertTrue(service.confirmProcessingOrderRefund(pending));
        assertEquals(800, o.getRefundedCents());
        verify(memberService).clawbackPointsOnRefund(10001L, 800, "O-PROC-2", "rk-proc-2");
    }

    @Test
    void repeatedProcessing_touchesSameRowConditionallyWithoutAdvancingRefund() {
        CabinetOrder o = order("O-PROC-4");
        PaymentOperation pending = new PaymentOperation();
        pending.setOperationId("OP-PROC-4");
        pending.setOrderId("O-PROC-4");
        pending.setStatus("REFUND_PROCESSING");
        pending.setIdempotencyKey("rk-proc-4");
        pending.setReason("退款 [refund:PROCESSING]");
        when(cabinetOrderRepository.findByIdForUpdate("O-PROC-4")).thenReturn(Optional.of(o));
        when(paymentOperationRepository.findByIdempotencyKey("rk-proc-4"))
                .thenReturn(Optional.of(pending));
        OrderPaymentService.OrderRefundPrep prep = new OrderPaymentService.OrderRefundPrep(
                "O-PROC-4", 10001L, 800, 800, "退款", "rk-proc-4", "WECHAT", "PT-O-PROC-4", 800, false, true);

        assertFalse(service.finalizeLiveOrderRefund(prep, "PROCESSING"));

        verify(paymentOperationRepository).touchProcessingRefund(
                "OP-PROC-4", "退款 [refund:PROCESSING]", "退款 [refund:PROCESSING]");
        assertEquals(0, o.getRefundedCents());
        verify(cabinetOrderRepository, never()).updateById(any());
    }

    @Test
    void retryAfterProcessing_successPromotesSameOperationWithoutDoubleRefund() {
        CabinetOrder o = order("O-PROC-3");
        PaymentOperation pending = new PaymentOperation();
        pending.setOperationId("OP-PROC-3");
        pending.setOrderId("O-PROC-3");
        pending.setStatus("REFUND_PROCESSING");
        pending.setIdempotencyKey("rk-proc-3");
        pending.setReason("退款 [refund:PROCESSING]");
        when(cabinetOrderRepository.findByIdForUpdate("O-PROC-3")).thenReturn(Optional.of(o));
        when(paymentOperationRepository.findByIdempotencyKey("rk-proc-3"))
                .thenReturn(Optional.of(pending));
        when(paymentOperationRepository.finishProcessingRefund(
                "OP-PROC-3", "退款 [refund:PROCESSING]", "退款 [refund:SUCCESS]", "COMPLETED"))
                .thenReturn(1);
        OrderPaymentService.OrderRefundPrep prep = new OrderPaymentService.OrderRefundPrep(
                "O-PROC-3", 10001L, 800, 800, "退款", "rk-proc-3", "WECHAT", "PT-O-PROC-3", 800, false, true);

        assertTrue(service.finalizeLiveOrderRefund(prep, "SUCCESS"));
        pending.setStatus("COMPLETED");
        assertTrue(service.finalizeLiveOrderRefund(prep, "SUCCESS"));

        assertEquals(800, o.getRefundedCents());
        verify(cabinetOrderRepository).updateById(o);
        verify(paymentOperationRepository, never()).save(any(PaymentOperation.class));
    }

    @Test
    void finalize_successStatus_recordsCompleted() {
        CabinetOrder o = order("O-OK-1");
        when(cabinetOrderRepository.findByIdForUpdate("O-OK-1")).thenReturn(Optional.of(o));
        when(paymentOperationRepository.findByIdempotencyKey("rk-ok")).thenReturn(Optional.empty());

        OrderPaymentService.OrderRefundPrep prep = new OrderPaymentService.OrderRefundPrep(
                "O-OK-1", 10001L, 800, 800, "退款", "rk-ok", "WECHAT", "PT-O-OK-1", 800, false, true);

        assertTrue(service.finalizeLiveOrderRefund(prep, "SUCCESS"));
        assertEquals(800, o.getRefundedCents());
        ArgumentCaptor<PaymentOperation> saved = ArgumentCaptor.forClass(PaymentOperation.class);
        verify(paymentOperationRepository).save(saved.capture());
        assertEquals("COMPLETED", saved.getValue().getStatus());
    }
}
