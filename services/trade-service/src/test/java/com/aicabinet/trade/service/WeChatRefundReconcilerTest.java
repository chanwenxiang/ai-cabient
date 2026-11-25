package com.aicabinet.trade.service;

import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.payment.WeChatPayClient;
import com.aicabinet.trade.payment.WeChatPayV3Client;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeChatRefundReconcilerTest {

    @Mock PaymentOperationMapper paymentOperationMapper;
    @Mock WeChatPayClient weChatPayClient;
    @Mock WeChatPayV3Client weChatPayV3Client;
    @Mock OpsAlertDispatcher opsAlertDispatcher;
    @Mock OrderPaymentService orderPaymentService;
    @Mock PaymentService paymentService;
    @Mock BalanceRefundService balanceRefundService;
    @Mock RefundResumeService refundResumeService;

    private WeChatRefundReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new WeChatRefundReconciler(
                paymentOperationMapper, weChatPayClient, opsAlertDispatcher, orderPaymentService);
        when(weChatPayClient.v3()).thenReturn(weChatPayV3Client);
        when(weChatPayV3Client.properties()).thenReturn(new WeChatPayProperties(
                true, "app", "mch", "https://example.invalid/notify", "key", "serial", "private", "cert", false));
    }

    private PaymentOperation pending(String status) {
        PaymentOperation op = new PaymentOperation();
        op.setOperationId("OP-1");
        op.setOrderId("ORDER-1");
        op.setUserId(1L);
        op.setOperationType("REFUND");
        op.setAmountCents(200);
        op.setChannel("WECHAT");
        op.setStatus(status);
        op.setIdempotencyKey("REFUND:ORDER-1:200");
        op.setGatewayTradeNo("12345678901234567890");
        op.setReason("退款 [refund:PROCESSING]");
        return op;
    }

    @Test
    void processingThenAbnormal_marksFailedWithoutLocalRefund() {
        PaymentOperation op = pending("REFUND_PROCESSING");
        when(paymentOperationMapper.findWechatRefundsAwaitingConfirmation()).thenReturn(List.of(op));
        when(weChatPayV3Client.queryRefund("ORDER-1", op.getGatewayTradeNo()))
                .thenReturn(new ObjectMapper().valueToTree(Map.of("status", "ABNORMAL")));
        when(paymentOperationMapper.finishProcessingRefund(
                op.getOperationId(), op.getReason(), "退款 [refund:ABNORMAL]", "REFUND_FAILED"))
                .thenReturn(1);

        assertEquals(1, reconciler.advanceProcessingRefunds());

        verify(orderPaymentService, never()).confirmProcessingOrderRefund(any());
        verify(opsAlertDispatcher).send(eq("WECHAT_REFUND_ABNORMAL"), anyString(),
                eq("opId=OP-1 outRefundNo=12345678901234567890 本地未记已退"));
    }

    @Test
    void processingThenSuccess_confirmsLocalRefund() {
        PaymentOperation op = pending("REFUND_PROCESSING");
        when(paymentOperationMapper.findWechatRefundsAwaitingConfirmation()).thenReturn(List.of(op));
        when(weChatPayV3Client.queryRefund("ORDER-1", op.getGatewayTradeNo()))
                .thenReturn(new ObjectMapper().valueToTree(Map.of("status", "SUCCESS")));
        when(orderPaymentService.confirmProcessingOrderRefund(op)).thenReturn(true);

        assertEquals(1, reconciler.advanceProcessingRefunds());

        verify(orderPaymentService).confirmProcessingOrderRefund(op);
        verify(opsAlertDispatcher, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void rechargeProcessingThenSuccess_finalizesRechargeAndBalanceSlice() {
        PaymentOperation op = pending("REFUND_PROCESSING");
        op.setOrderId(null);
        op.setOperationType("RECHARGE_REFUND");
        op.setReason("充值退款 [refund:PROCESSING] #RC-1");
        WeChatRefundReconciler service = new WeChatRefundReconciler(
                paymentOperationMapper, weChatPayClient, opsAlertDispatcher, orderPaymentService,
                paymentService, balanceRefundService, refundResumeService);
        when(paymentOperationMapper.findWechatRefundsAwaitingConfirmation()).thenReturn(List.of(op));
        when(weChatPayV3Client.queryRefund("RC-1", op.getGatewayTradeNo()))
                .thenReturn(new ObjectMapper().valueToTree(Map.of("status", "SUCCESS")));
        when(paymentService.confirmRechargeChannelRefund(
                "RC-1", op, "SUCCESS", "充值退款 [refund:SUCCESS] #RC-1")).thenReturn(true);
        when(balanceRefundService.hasChannelSlice(op.getGatewayTradeNo())).thenReturn(true);

        assertEquals(1, service.advanceProcessingRefunds());

        verify(paymentService).confirmRechargeChannelRefund(
                "RC-1", op, "SUCCESS", "充值退款 [refund:SUCCESS] #RC-1");
        verify(balanceRefundService).confirmChannelSliceSuccess(op.getGatewayTradeNo());
        verify(paymentService).markRechargeRefundContextDone(op);
    }

    @Test
    void rechargeProcessingThenAbnormal_marksSliceFailedWithoutFinalizingRecharge() {
        PaymentOperation op = pending("REFUND_PROCESSING");
        op.setOrderId(null);
        op.setOperationType("RECHARGE_REFUND");
        op.setReason("充值退款 [refund:PROCESSING] #RC-1");
        WeChatRefundReconciler service = new WeChatRefundReconciler(
                paymentOperationMapper, weChatPayClient, opsAlertDispatcher, orderPaymentService,
                paymentService, balanceRefundService, refundResumeService);
        when(paymentOperationMapper.findWechatRefundsAwaitingConfirmation()).thenReturn(List.of(op));
        when(weChatPayV3Client.queryRefund("RC-1", op.getGatewayTradeNo()))
                .thenReturn(new ObjectMapper().valueToTree(Map.of("status", "ABNORMAL")));
        when(paymentService.confirmRechargeChannelRefund(
                "RC-1", op, "ABNORMAL", "充值退款 [refund:ABNORMAL] #RC-1")).thenReturn(true);
        when(balanceRefundService.hasChannelSlice(op.getGatewayTradeNo())).thenReturn(true);

        assertEquals(1, service.advanceProcessingRefunds());

        verify(paymentService).confirmRechargeChannelRefund(
                "RC-1", op, "ABNORMAL", "充值退款 [refund:ABNORMAL] #RC-1");
        verify(balanceRefundService).failChannelSlice(op.getGatewayTradeNo(), "充值退款 [refund:ABNORMAL] #RC-1");
        verify(paymentService).markRechargeRefundContextDone(op);
        verify(paymentService, never()).finalizeChannelPartialRefund(anyString(), anyInt(), anyString());
    }

    @Test
    void legacyCompletedThenAbnormal_requiresManualDownstreamReview() {
        PaymentOperation op = pending("COMPLETED");
        when(paymentOperationMapper.findWechatRefundsAwaitingConfirmation()).thenReturn(List.of(op));
        when(weChatPayV3Client.queryRefund("ORDER-1", op.getGatewayTradeNo()))
                .thenReturn(new ObjectMapper().valueToTree(Map.of("status", "ABNORMAL")));

        assertEquals(1, reconciler.advanceProcessingRefunds());

        verify(paymentOperationMapper).updateById(op);
        verify(opsAlertDispatcher).send(eq("WECHAT_REFUND_ABNORMAL_LEGACY_LOCAL_REVIEW"), anyString(),
                eq("opId=OP-1 outRefundNo=12345678901234567890 存量本地已退，须人工核对券/分账/订单冲正"));
    }
}
