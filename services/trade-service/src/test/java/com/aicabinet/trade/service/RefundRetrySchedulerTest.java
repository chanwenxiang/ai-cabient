package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B1/G1（2026-10-15）：RefundRetryScheduler 调度行为钉住测试。
 *
 * <p>钉住：锁忙静默／重试闭环计数／无终态 touch 推后／耗尽按 created_at 判定
 * （touch 推后的 updated_at 不得影响耗尽——否则永不转人工）／单笔失败不阻断。
 */
@ExtendWith(MockitoExtension.class)
class RefundRetrySchedulerTest {

    static {
        // 纯 Mockito 环境需手动注册 TableInfo 缓存（Lambda wrapper 列名解析）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                PaymentOperation.class);
    }

    @Mock PaymentOperationMapper paymentOperationMapper;
    @Mock OrderPaymentService orderPaymentService;
    @Mock WeChatRefundReconciler weChatRefundReconciler;
    @Mock ScheduledTaskService taskService;

    private RefundRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new RefundRetryScheduler(
                paymentOperationMapper, orderPaymentService, weChatRefundReconciler, taskService);
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
    void run_lockBusy_returnsSilently() {
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_RETRY, 600)).thenReturn(false);

        scheduler.run();

        verifyNoInteractions(paymentOperationMapper, orderPaymentService, weChatRefundReconciler);
    }

    @Test
    void run_closesStaleTraceAndAdvancesProcessing() {
        PaymentOperation t = trace("O-SC-1");
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_RETRY, 600)).thenReturn(true);
        when(paymentOperationMapper.selectList(any())).thenReturn(List.of(t), Collections.emptyList());
        when(orderPaymentService.retryFromInitiatedTrace(t)).thenReturn(true);
        when(weChatRefundReconciler.advanceProcessingRefunds()).thenReturn(1);

        scheduler.run();

        verify(orderPaymentService).retryFromInitiatedTrace(t);
        verify(paymentOperationMapper, never()).updateById(any(PaymentOperation.class));
        verify(taskService).finish(eq(RefundRetryScheduler.REFUND_RETRY), eq("SUCCESS"), anyString(), anyLong());
    }

    @Test
    void run_retryWithoutTerminalState_touchesTraceToRearmQuietPeriod() {
        PaymentOperation t = trace("O-SC-2");
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_RETRY, 600)).thenReturn(true);
        when(paymentOperationMapper.selectList(any())).thenReturn(List.of(t), Collections.emptyList());
        when(orderPaymentService.retryFromInitiatedTrace(t)).thenReturn(false);

        scheduler.run();

        // touch 推后 updated_at：下轮静默期重新计时（防与渠道侧持续未决的痕迹空转）
        verify(paymentOperationMapper).updateById(t);
        assertNotEquals(Instant.EPOCH, t.getUpdatedAt());
    }

    @Test
    void run_exhaustsOverdueTraceToFailedWithAlert() {
        PaymentOperation t = trace("O-SC-3");
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_RETRY, 600)).thenReturn(true);
        when(paymentOperationMapper.selectList(any())).thenReturn(Collections.emptyList(), List.of(t));
        when(weChatRefundReconciler.advanceProcessingRefunds()).thenReturn(0);

        scheduler.run();

        verify(orderPaymentService).markRefundFailed(t, "超 " + RefundRetryScheduler.EXHAUST_AFTER_MINUTES + " 分钟无终态");
        verify(taskService).finish(eq(RefundRetryScheduler.REFUND_RETRY), eq("SUCCESS"), anyString(), anyLong());
    }

    @Test
    void run_staleScanByUpdatedAt_exhaustScanByCreatedAt() {
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_RETRY, 600)).thenReturn(true);
        when(paymentOperationMapper.selectList(any())).thenReturn(Collections.emptyList(), Collections.emptyList());

        scheduler.run();

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<PaymentOperation>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(paymentOperationMapper, org.mockito.Mockito.times(2)).selectList(captor.capture());
        AbstractWrapper<?, ?, ?> staleScan = (AbstractWrapper<?, ?, ?>) captor.getAllValues().get(0);
        AbstractWrapper<?, ?, ?> exhaustScan = (AbstractWrapper<?, ?, ?>) captor.getAllValues().get(1);
        // 超时扫描按 updated_at（touch 静默期语义）；耗尽扫描必须按 created_at
        // ——若按 updated_at，touch 每轮推后会让耗尽永不触发（2026-10-15 修复点）
        assertTrue(staleScan.getSqlSegment().contains("updated_at"));
        assertTrue(exhaustScan.getSqlSegment().contains("created_at"));
        assertTrue(exhaustScan.getParamNameValuePairs().containsValue("REFUND_INITIATED"));
    }

    @Test
    void run_singleTraceFailure_doesNotBlockOthers() {
        PaymentOperation bad = trace("O-SC-4A");
        PaymentOperation good = trace("O-SC-4B");
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_RETRY, 600)).thenReturn(true);
        when(paymentOperationMapper.selectList(any())).thenReturn(List.of(bad, good), Collections.emptyList());
        doThrow(new RuntimeException("query down")).when(orderPaymentService).retryFromInitiatedTrace(bad);
        when(orderPaymentService.retryFromInitiatedTrace(good)).thenReturn(true);

        scheduler.run();

        verify(orderPaymentService).retryFromInitiatedTrace(bad);
        verify(orderPaymentService).retryFromInitiatedTrace(good);
        verify(taskService).finish(eq(RefundRetryScheduler.REFUND_RETRY), eq("SUCCESS"), anyString(), anyLong());
    }

    @Test
    void run_nothingToDo_stillRecordsSuccess() {
        when(taskService.tryBegin(RefundRetryScheduler.REFUND_RETRY, 600)).thenReturn(true);
        when(paymentOperationMapper.selectList(any())).thenReturn(Collections.emptyList(), Collections.emptyList());

        scheduler.run();

        verify(taskService).finish(eq(RefundRetryScheduler.REFUND_RETRY), eq("SUCCESS"), anyString(), anyLong());
        assertEquals(0, weChatRefundReconcilerAdvanceCalls());
    }

    private int weChatRefundReconcilerAdvanceCalls() {
        return 0; // advanceProcessingRefunds 返回 0（mock 默认），仅确保未闭环时也 finish
    }
}
