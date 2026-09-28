package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CompensationTask;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.CompensationTaskMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.payment.WeChatProfitSharingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** L2-3：TCC/分布式事务重试下线后，调度器仅保留分账回退补偿语义。 */
@ExtendWith(MockitoExtension.class)
class CompensationTaskSchedulerTest {

    @Mock private CompensationTaskMapper taskRepository;
    @Mock private MerchantMapper merchantRepository;
    @Mock private OrderRevenueSplitMapper splitRepository;
    @Mock private WeChatProfitSharingService profitSharingService;
    @Mock private ProfitSharingReturnAlertService profitSharingReturnAlertService;

    @Mock private ScheduledTaskService taskService;

    private CompensationTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CompensationTaskScheduler(taskRepository,
                taskService, splitRepository, merchantRepository, profitSharingService,
                profitSharingReturnAlertService, null);
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

    /** L2-3：未知任务类型防御性终态，不再尝试分布式事务补偿。 */
    @Test
    void processTask_unknownType_shouldFailDefensively() {
        CompensationTask task = pendingReturnTask();
        task.setTaskType("SOME_LEGACY_TYPE");

        scheduler.processTask(task);

        assertEquals("FAILED", task.getStatus());
        assertTrue(task.getResult().contains("unsupported task type"));
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
