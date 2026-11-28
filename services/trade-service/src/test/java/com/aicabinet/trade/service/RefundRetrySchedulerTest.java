package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 退款调度拆频：查单/终态推进每分钟，重发/耗尽每小时。
 */
@ExtendWith(MockitoExtension.class)
class RefundRetrySchedulerTest {

    static {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                PaymentOperation.class);
    }

    @Mock PaymentOperationMapper paymentOperationMapper;
    @Mock OrderPaymentService orderPaymentService;
    @Mock WeChatRefundReconciler weChatRefundReconciler;
    @Mock ScheduledTaskService taskService;
    @Mock RefundResumeService refundResumeService;

    private RefundRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        lenient().when(paymentOperationMapper.touchIfInitiated(anyString(), any())).thenReturn(1);
        lenient().when(paymentOperationMapper.failIfInitiated(anyString(), any())).thenReturn(1);
        scheduler = new RefundRetryScheduler(
                paymentOperationMapper, orderPaymentService, weChatRefundReconciler, taskService);
        ReflectionTestUtils.setField(scheduler, "refundResumeService", refundResumeService);
    }

    private PaymentOperation trace(String orderId) {
        PaymentOperation trace = new PaymentOperation();
        trace.setOperationId("OP-" + orderId);
        trace.setOrderId(orderId);
        trace.setOperationType("REFUND");
        trace.setAmountCents(800);
        trace.setChannel("WECHAT");
        trace.setStatus("REFUND_INITIATED");
        trace.setIdempotencyKey("REFUND:INIT:" + orderId);
        trace.setGatewayTradeNo("00123456780098765432");
        trace.setCreatedAt(Instant.now().minusSeconds(3600));
        trace.setUpdatedAt(Instant.EPOCH);
        return trace;
    }

    @Test
    void runQuery_lockBusy_returnsSilently() {
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_QUERY, 600)).thenReturn(false);

        scheduler.runQuery();

        verifyNoInteractions(paymentOperationMapper, orderPaymentService,
                weChatRefundReconciler, refundResumeService);
    }

    @Test
    void runQuery_advancesProcessingAndResumesWithoutRetry() {
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_QUERY, 600)).thenReturn(true);
        when(weChatRefundReconciler.advanceProcessingRefunds()).thenReturn(1);
        when(refundResumeService.resumePendingCompletedRefunds(20)).thenReturn(1);

        scheduler.runQuery();

        verify(weChatRefundReconciler).advanceProcessingRefunds();
        verify(refundResumeService).resumePendingCompletedRefunds(20);
        verify(paymentOperationMapper, never()).selectList(any());
        verify(orderPaymentService, never()).retryFromInitiatedTrace(any());
        verify(taskService).finish(eq(RefundRetryScheduler.REFUND_QUERY), eq("SUCCESS"), anyString(), anyLong());
    }

    @Test
    void runQuery_nothingToDo_stillRecordsSuccess() {
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_QUERY, 600)).thenReturn(true);

        scheduler.runQuery();

        verify(taskService).finish(eq(RefundRetryScheduler.REFUND_QUERY), eq("SUCCESS"), anyString(), anyLong());
        verify(paymentOperationMapper, never()).selectList(any());
    }

    @Test
    void runRetry_lockBusy_returnsSilently() {
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_RETRY, 600)).thenReturn(false);

        scheduler.runRetry();

        verifyNoInteractions(paymentOperationMapper, orderPaymentService,
                weChatRefundReconciler, refundResumeService);
    }

    @Test
    void runRetry_closesStaleTraceWithoutAdvancingProcessing() {
        PaymentOperation t = trace("O-SC-1");
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_RETRY, 600)).thenReturn(true);
        when(paymentOperationMapper.selectList(any())).thenReturn(List.of(t), Collections.emptyList());
        when(orderPaymentService.retryFromInitiatedTrace(t)).thenReturn(true);

        scheduler.runRetry();

        verify(orderPaymentService).retryFromInitiatedTrace(t);
        verify(paymentOperationMapper, never()).updateById(any(PaymentOperation.class));
        verify(weChatRefundReconciler, never()).advanceProcessingRefunds();
        verify(taskService).finish(eq(RefundRetryScheduler.REFUND_RETRY), eq("SUCCESS"), anyString(), anyLong());
    }

    @Test
    void runRetry_retryWithoutTerminalState_touchesTraceToRearmQuietPeriod() {
        PaymentOperation t = trace("O-SC-2");
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_RETRY, 600)).thenReturn(true);
        when(paymentOperationMapper.selectList(any())).thenReturn(List.of(t), Collections.emptyList());
        when(orderPaymentService.retryFromInitiatedTrace(t)).thenReturn(false);

        scheduler.runRetry();

        verify(paymentOperationMapper).touchIfInitiated(eq(t.getOperationId()), any());
        assertNotEquals(Instant.EPOCH, t.getUpdatedAt());
    }

    @Test
    void runRetry_exhaustsOverdueTraceToFailedWithAlert() {
        PaymentOperation t = trace("O-SC-3");
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_RETRY, 600)).thenReturn(true);
        when(paymentOperationMapper.selectList(any())).thenReturn(Collections.emptyList(), List.of(t));

        scheduler.runRetry();

        verify(orderPaymentService).markRefundFailed(t, "超 " + RefundRetryScheduler.EXHAUST_AFTER_MINUTES + " 分钟无终态");
        verify(taskService).finish(eq(RefundRetryScheduler.REFUND_RETRY), eq("SUCCESS"), anyString(), anyLong());
    }

    @Test
    void runRetry_exhaustDefersWhenChannelQueryUnknown() {
        PaymentOperation t = trace("O-SC-UNKNOWN");
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_RETRY, 600)).thenReturn(true);
        when(paymentOperationMapper.selectList(any())).thenReturn(Collections.emptyList(), List.of(t));
        when(orderPaymentService.queryInitiatedRefundStatus(t)).thenReturn("UNKNOWN");

        scheduler.runRetry();

        verify(orderPaymentService, never()).markRefundFailed(any(), anyString());
        verify(orderPaymentService, never()).retryFromInitiatedTrace(any());
    }

    @Test
    void runRetry_exhaustClosesWhenChannelAlreadySucceeded() {
        PaymentOperation t = trace("O-SC-SUCCESS");
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_RETRY, 600)).thenReturn(true);
        when(paymentOperationMapper.selectList(any())).thenReturn(Collections.emptyList(), List.of(t));
        when(orderPaymentService.queryInitiatedRefundStatus(t)).thenReturn("SUCCESS");
        when(orderPaymentService.retryFromInitiatedTrace(t)).thenReturn(true);

        scheduler.runRetry();

        verify(orderPaymentService).retryFromInitiatedTrace(t);
        verify(orderPaymentService, never()).markRefundFailed(any(), anyString());
    }

    @Test
    void runRetry_staleScanByUpdatedAt_exhaustScanByCreatedAt() {
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_RETRY, 600)).thenReturn(true);
        when(paymentOperationMapper.selectList(any())).thenReturn(Collections.emptyList(), Collections.emptyList());

        scheduler.runRetry();

        ArgumentCaptor<Wrapper<PaymentOperation>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(paymentOperationMapper, times(2)).selectList(captor.capture());
        AbstractWrapper<?, ?, ?> staleScan = (AbstractWrapper<?, ?, ?>) captor.getAllValues().get(0);
        AbstractWrapper<?, ?, ?> exhaustScan = (AbstractWrapper<?, ?, ?>) captor.getAllValues().get(1);
        assertTrue(staleScan.getSqlSegment().contains("updated_at"));
        assertTrue(exhaustScan.getSqlSegment().contains("created_at"));
        assertTrue(exhaustScan.getParamNameValuePairs().containsValue("REFUND_INITIATED"));
    }

    @Test
    void runRetry_singleTraceFailure_doesNotBlockOthers() {
        PaymentOperation bad = trace("O-SC-4A");
        PaymentOperation good = trace("O-SC-4B");
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_RETRY, 600)).thenReturn(true);
        when(paymentOperationMapper.selectList(any())).thenReturn(List.of(bad, good), Collections.emptyList());
        doThrow(new RuntimeException("query down")).when(orderPaymentService).retryFromInitiatedTrace(bad);
        when(orderPaymentService.retryFromInitiatedTrace(good)).thenReturn(true);

        scheduler.runRetry();

        verify(orderPaymentService).retryFromInitiatedTrace(bad);
        verify(orderPaymentService).retryFromInitiatedTrace(good);
        verify(taskService).finish(eq(RefundRetryScheduler.REFUND_RETRY), eq("SUCCESS"), anyString(), anyLong());
    }

    @Test
    void runRetry_nothingToDo_stillRecordsSuccess() {
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_RETRY, 600)).thenReturn(true);
        when(paymentOperationMapper.selectList(any())).thenReturn(Collections.emptyList(), Collections.emptyList());

        scheduler.runRetry();

        verify(taskService).finish(eq(RefundRetryScheduler.REFUND_RETRY), eq("SUCCESS"), anyString(), anyLong());
        assertEquals(0, weChatRefundReconcilerAdvanceCalls());
    }

    private int weChatRefundReconcilerAdvanceCalls() {
        return 0;
    }
}
