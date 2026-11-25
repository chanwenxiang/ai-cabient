package com.aicabinet.trade.service;

import com.aicabinet.trade.config.ReconciliationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationSchedulerTest {

    @Mock private ReconciliationService reconciliationService;
    @Mock private ReconciliationProperties properties;
    @Mock private ScheduledTaskService taskService;
    @Mock private WeChatRefundReconciler refundReconciler;
    @Mock private MerchantWithdrawService merchantWithdrawService;
    @Mock private LineWithdrawService lineWithdrawService;
    @Mock private PlatformFundBillReconciler fundBillReconciler;

    @Test
    void dailyFailure_stillAdvancesProcessingRefundsAndRecordsFailure() {
        ReconciliationScheduler scheduler = new ReconciliationScheduler(
                reconciliationService, properties, taskService, refundReconciler,
                merchantWithdrawService, lineWithdrawService, fundBillReconciler);
        when(taskService.tryBegin("reconciliation", 1800)).thenReturn(true);
        when(properties.scheduledEnabled()).thenReturn(true);
        when(reconciliationService.runDaily(any(), any(), eq("WECHAT")))
                .thenThrow(new IllegalStateException("tradebill 500"));

        scheduler.runDailyReconciliation();

        verify(refundReconciler).advanceProcessingRefunds();
        verify(taskService).finish(eq("reconciliation"), eq("FAILED"),
                contains("对账失败"), anyLong());
    }
}
