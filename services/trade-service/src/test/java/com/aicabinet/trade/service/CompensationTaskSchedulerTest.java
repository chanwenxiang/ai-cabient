package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CompensationTask;
import com.aicabinet.trade.domain.DistributedTransaction;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.CompensationTaskMapper;
import com.aicabinet.trade.mapper.DistributedTransactionMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.payment.WeChatProfitSharingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompensationTaskSchedulerTest {

    @Mock private CompensationTaskMapper taskRepository;
    @Mock private MerchantMapper merchantRepository;
    @Mock private OrderRevenueSplitMapper splitRepository;
    @Mock private WeChatProfitSharingService profitSharingService;
    @Mock private ProfitSharingReturnAlertService profitSharingReturnAlertService;
    @Mock private OpsAlertDispatcher alertDispatcher;

    @Mock private DistributedTransactionMapper txRepository;
    @Mock private TccTransactionCoordinator txCoordinator;
    @Mock private ScheduledTaskService taskService;

    private CompensationTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CompensationTaskScheduler(taskRepository, txRepository, txCoordinator,
                taskService, splitRepository, merchantRepository, profitSharingService,
                profitSharingReturnAlertService, alertDispatcher, null);
        ReflectionTestUtils.setField(scheduler, "self", scheduler);
    }

    @Test
    void profitSharingReturnBackoffSeconds_shouldCapAt900() {
        assertEquals(60L, CompensationTaskScheduler.profitSharingReturnBackoffSeconds(1));
        assertEquals(240L, CompensationTaskScheduler.profitSharingReturnBackoffSeconds(2));
        assertEquals(540L, CompensationTaskScheduler.profitSharingReturnBackoffSeconds(3));
        assertEquals(900L, CompensationTaskScheduler.profitSharingReturnBackoffSeconds(4));
        assertEquals(900L, CompensationTaskScheduler.profitSharingReturnBackoffSeconds(10));
    }

    @Test
    void processTask_profitSharingReturn_shouldCompleteWhenReturnConfirmed() {
        CompensationTask task = pendingReturnTask();
        OrderRevenueSplit split = pendingSplit();

        when(splitRepository.selectById("SPLIT-X")).thenReturn(split, clearedSplit());
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant()));
        when(profitSharingService.refreshPendingReturn(any())).thenReturn(true);

        scheduler.processTask(task);

        assertEquals("COMPLETED", task.getStatus());
        assertEquals("return confirmed", task.getResult());
    }

    @Test
    void processTask_profitSharingReturn_shouldFailAfterMaxRetries() {
        CompensationTask task = pendingReturnTask();
        task.setRetryCount(4);
        OrderRevenueSplit split = pendingSplit();
        split.setFailureReason("分账回退未成功需人工处理");

        when(splitRepository.selectById("SPLIT-X")).thenReturn(split);
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant()));
        when(profitSharingService.refreshPendingReturn(any())).thenReturn(false);
        when(profitSharingService.retryFailedReturns(anyList(), anyMap())).thenReturn(0);

        scheduler.processTask(task);

        assertEquals("FAILED", task.getStatus());
        assertEquals(5, task.getRetryCount());
        assertTrue(task.getResult().contains("max retries exceeded"));
        verify(profitSharingReturnAlertService).sendCompensationExhausted(task, split);
        verify(taskRepository, times(2)).save(task);
    }

    @Test
    void processTask_profitSharingReturn_shouldDeferWithBackoff() {
        CompensationTask task = pendingReturnTask();
        OrderRevenueSplit split = pendingSplit();
        split.setFailureReason("分账回退未成功需人工处理");

        when(splitRepository.selectById("SPLIT-X")).thenReturn(split);
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant()));
        when(profitSharingService.refreshPendingReturn(any())).thenReturn(false);
        when(profitSharingService.retryFailedReturns(anyList(), anyMap())).thenReturn(0);

        scheduler.processTask(task);

        assertEquals("PENDING", task.getStatus());
        assertEquals(1, task.getRetryCount());
        assertTrue(task.getResult().contains("retry=1/5"));
        verify(taskRepository, times(2)).save(task);
    }

    @Test
    void retryFailedTransactions_cancelCompensation_shouldExecuteRealRetry() {
        DistributedTransaction tx = pendingTx("TX-1", "ORDER_CANCEL");
        tx.setCompensationSql("CANCEL");
        DistributedTransaction cancelled = pendingTx("TX-1", "ORDER_CANCEL");
        cancelled.setCompensationSql("CANCEL");
        cancelled.setStatus("CANCELLED");
        when(taskService.tryBegin(anyString(), anyLong())).thenReturn(true);
        when(txRepository.findRetryableTransactions()).thenReturn(List.of(tx));
        when(txRepository.findById("TX-1")).thenReturn(Optional.of(cancelled));

        scheduler.retryFailedTransactions();

        verify(txCoordinator).cancelTransaction("TX-1");
        verify(txRepository, never()).save(any());
        verify(taskService).finish(eq("compensation-retry"), eq("SUCCESS"), anyString(), anyLong());
    }

    @Test
    void retryFailedTransactions_cancelCompensation_shouldMarkNeedManualAndAlertWhenExhausted() {
        DistributedTransaction tx = pendingTx("TX-2", "ORDER_CANCEL");
        tx.setCompensationSql("CANCEL");
        tx.setRetryCount(4);
        when(taskService.tryBegin(anyString(), anyLong())).thenReturn(true);
        when(txRepository.findRetryableTransactions()).thenReturn(List.of(tx));
        doThrow(new IllegalStateException("db down")).when(txCoordinator).cancelTransaction("TX-2");

        scheduler.retryFailedTransactions();

        assertEquals("NEED_MANUAL", tx.getStatus());
        assertEquals(5, tx.getRetryCount());
        assertTrue(tx.getErrorMessage().contains("db down"));
        verify(alertDispatcher).trySend(eq("COMPENSATION_TX_STUCK"), anyString(), anyString(), anyMap());
        verify(taskService).finish(eq("compensation-retry"), eq("SUCCESS"), anyString(), anyLong());
    }

    @Test
    void retryFailedTransactions_unsupportedType_shouldMarkNeedManualAndAlert() {
        DistributedTransaction tx = pendingTx("TX-3", "UNKNOWN_TYPE");
        when(taskService.tryBegin(anyString(), anyLong())).thenReturn(true);
        when(txRepository.findRetryableTransactions()).thenReturn(List.of(tx));

        scheduler.retryFailedTransactions();

        assertEquals("NEED_MANUAL", tx.getStatus());
        verify(txCoordinator, never()).cancelTransaction(anyString());
        verify(alertDispatcher).trySend(eq("COMPENSATION_TX_STUCK"), anyString(), anyString(), anyMap());
    }

    @Test
    void retryFailedTransactions_cancelCompensation_shouldDeferBelowMaxRetry() {
        DistributedTransaction tx = pendingTx("TX-4", "ORDER_CANCEL");
        tx.setCompensationSql("CANCEL");
        DistributedTransaction stillPending = pendingTx("TX-4", "ORDER_CANCEL");
        stillPending.setCompensationSql("CANCEL");
        when(taskService.tryBegin(anyString(), anyLong())).thenReturn(true);
        when(txRepository.findRetryableTransactions()).thenReturn(List.of(tx));
        when(txRepository.findById("TX-4")).thenReturn(Optional.of(stillPending));

        scheduler.retryFailedTransactions();

        assertEquals("PENDING", tx.getStatus());
        assertEquals(1, tx.getRetryCount());
        verify(alertDispatcher, never()).trySend(anyString(), anyString(), anyString(), anyMap());
    }

    private static CompensationTask pendingReturnTask() {
        CompensationTask task = new CompensationTask();
        task.setTaskId(99L);
        task.setTxId("SPLIT-X");
        task.setTaskType(ProfitSharingReturnCompensationService.TASK_TYPE);
        task.setStatus("PENDING");
        task.setRetryCount(0);
        return task;
    }

    private static DistributedTransaction pendingTx(String txId, String txType) {
        DistributedTransaction tx = new DistributedTransaction();
        tx.setTxId(txId);
        tx.setTxType(txType);
        tx.setStatus("PENDING");
        tx.setRetryCount(0);
        tx.setMaxRetry(5);
        return tx;
    }

    private static OrderRevenueSplit pendingSplit() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-X");
        split.setMerchantId("M-1");
        split.setWechatPendingReturnNo("PSR-X");
        split.setWechatPendingReturnCents(500L);
        return split;
    }

    private static OrderRevenueSplit clearedSplit() {
        OrderRevenueSplit split = pendingSplit();
        split.setWechatPendingReturnNo(null);
        split.setWechatPendingReturnCents(null);
        split.setFailureReason(null);
        return split;
    }

    private static Merchant merchant() {
        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setWechatReceiverId("1900000109");
        return merchant;
    }
}
