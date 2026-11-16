package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChargePendingReconcilerTest {

    @Mock PaymentOperationMapper paymentOperationMapper;
    @Mock OrderPaymentService orderPaymentService;
    @Mock UnpaidOrderService unpaidOrderService;
    @Mock OpsExceptionService opsExceptionService;
    @Mock ScheduledTaskService taskService;

    private ChargePendingReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new ChargePendingReconciler(
                paymentOperationMapper, orderPaymentService, unpaidOrderService,
                opsExceptionService, taskService);
        when(taskService.tryBegin(ChargePendingReconciler.TASK_KEY, 300)).thenReturn(true);
    }

    @Test
    void success_reconcilesAndFinishesTask() {
        PaymentOperation trace = trace("WECHAT", 40, 20);
        when(paymentOperationMapper.findStaleChargePending(any(), eq(20))).thenReturn(List.of(trace));
        when(orderPaymentService.queryPendingCharge(trace)).thenReturn(
                new OrderPaymentService.ChargeQueryResult(
                        OrderPaymentService.ChargeQueryStatus.SUCCESS, "WECHAT", "TXN", 40));
        when(unpaidOrderService.reconcileChargePending("O-R", 100, "WECHAT", 40, "TXN")).thenReturn(true);

        reconciler.run();

        verify(unpaidOrderService).reconcileChargePending("O-R", 100, "WECHAT", 40, "TXN");
        verify(taskService).finish(eq(ChargePendingReconciler.TASK_KEY), eq("SUCCESS"),
                contains("收口 1 笔"), anyLong());
    }

    @Test
    void definitiveFailure_terminatesTraceWithoutReconcile() {
        PaymentOperation trace = trace("WECHAT", 40, 20);
        when(paymentOperationMapper.findStaleChargePending(any(), eq(20))).thenReturn(List.of(trace));
        when(orderPaymentService.queryPendingCharge(trace)).thenReturn(
                new OrderPaymentService.ChargeQueryResult(
                        OrderPaymentService.ChargeQueryStatus.FAILED, "WECHAT", null, null));

        reconciler.run();

        verify(orderPaymentService).finalizeChargePending("O-R", null);
        verify(unpaidOrderService, never()).reconcileChargePending(anyString(), anyInt(),
                anyString(), anyInt(), anyString());
    }

    @Test
    void unknown_keepsTraceAndAlertsOnceAtFifteenMinutes() {
        PaymentOperation trace = trace("WECHAT", 40, 16);
        when(paymentOperationMapper.findStaleChargePending(any(), eq(20))).thenReturn(List.of(trace));
        when(orderPaymentService.queryPendingCharge(trace)).thenReturn(
                OrderPaymentService.ChargeQueryResult.unknown("WECHAT"));

        reconciler.run();

        verify(unpaidOrderService, never()).reconcileChargePending(anyString(), anyInt(),
                anyString(), anyInt(), anyString());
        verify(orderPaymentService, never()).finalizeChargePending(anyString(), any());
        verify(opsExceptionService).report(eq("CHARGE_PENDING_UNRESOLVED"), eq("HIGH"),
                any(), eq("渠道扣款结果仍未确认"), contains("16 分钟"));
    }

    @Test
    void legacyTraceWithoutMetadata_isNotQueried() {
        PaymentOperation trace = trace(null, null, 20);
        when(paymentOperationMapper.findStaleChargePending(any(), eq(20))).thenReturn(List.of(trace));

        reconciler.run();

        verify(orderPaymentService, never()).queryPendingCharge(any());
        verify(unpaidOrderService, never()).reconcileChargePending(anyString(), anyInt(),
                anyString(), anyInt(), anyString());
    }

    private static PaymentOperation trace(String channel, Integer chargeCents, long ageMinutes) {
        PaymentOperation trace = new PaymentOperation();
        trace.setOrderId("O-R");
        trace.setUserId(10001L);
        trace.setAmountCents(100);
        trace.setAttemptedChannel(channel);
        trace.setChargeCents(chargeCents);
        trace.setStatus("CHARGE_PENDING");
        trace.setCreatedAt(Instant.now().minus(ageMinutes, ChronoUnit.MINUTES));
        trace.setUpdatedAt(trace.getCreatedAt());
        return trace;
    }
}
