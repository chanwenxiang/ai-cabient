package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CompensationTask;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.domain.OrderSplitReturnLeg;
import com.aicabinet.trade.mapper.CompensationTaskMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.OrderSplitReturnLegMapper;
import com.aicabinet.trade.payment.WeChatProfitSharingService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2-3：TCC/分布式事务重试下线后，调度器仅保留分账回退补偿语义。
 * R20-9：补偿任务按「腿」定位，新键 LEG:<legId>，存量裸 splitId 仍兼容。
 */
@ExtendWith(MockitoExtension.class)
class CompensationTaskSchedulerTest {

    @Mock private CompensationTaskMapper taskRepository;
    @Mock private MerchantMapper merchantRepository;
    @Mock private OrderRevenueSplitMapper splitRepository;
    @Mock private OrderSplitReturnLegMapper legRepository;
    @Mock private WeChatProfitSharingService profitSharingService;
    @Mock private ProfitSharingReturnAlertService profitSharingReturnAlertService;
    @Mock private ScheduledTaskService taskService;

    private CompensationTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CompensationTaskScheduler(taskRepository,
                taskService, splitRepository, merchantRepository, profitSharingService,
                profitSharingReturnAlertService, legRepository, null);
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
        CompensationTask task = pendingReturnTask(11L);
        OrderSplitReturnLeg leg = openLeg(11L, "PSR-X");
        when(legRepository.selectById(11L)).thenReturn(leg, confirmedLeg(leg));
        when(splitRepository.selectById("SPLIT-X")).thenReturn(pendingSplit());
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant()));
        when(profitSharingService.refreshPendingReturn(any(), eq(leg))).thenReturn(true);

        scheduler.processTask(task);

        assertEquals("COMPLETED", task.getStatus());
        assertEquals("return confirmed", task.getResult());
        verify(profitSharingService).refreshPendingReturn(any(), eq(leg));
    }

    @Test
    void processTask_profitSharingReturn_shouldFailAfterMaxRetries() {
        CompensationTask task = pendingReturnTask(11L);
        task.setRetryCount(4);
        OrderRevenueSplit split = pendingSplit();
        split.setFailureReason("分账回退未成功需人工处理");
        OrderSplitReturnLeg leg = openLeg(11L, "PSR-X");

        when(legRepository.selectById(11L)).thenReturn(leg);
        when(splitRepository.selectById("SPLIT-X")).thenReturn(split);
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant()));
        when(profitSharingService.refreshPendingReturn(any(), eq(leg))).thenReturn(false);
        when(profitSharingService.retryFailedReturnLegs(anyList(), anyMap())).thenReturn(0);
        when(legRepository.findOpenBySplitId("SPLIT-X")).thenReturn(List.of(leg));

        scheduler.processTask(task);

        assertEquals("FAILED", task.getStatus());
        assertEquals(5, task.getRetryCount());
        assertTrue(task.getResult().contains("max retries exceeded"));
        // R20-9：告警按腿聚合，钉住新重载（旧两参数入口不再从调度器调用）。
        verify(profitSharingReturnAlertService).sendCompensationExhausted(eq(task), eq(split), eq(List.of(leg)));
        verify(taskRepository, times(2)).save(task);
    }

    @Test
    void processTask_profitSharingReturn_shouldDeferWithBackoff() {
        CompensationTask task = pendingReturnTask(11L);
        OrderRevenueSplit split = pendingSplit();
        split.setFailureReason("分账回退未成功需人工处理");
        OrderSplitReturnLeg leg = openLeg(11L, "PSR-X");

        when(legRepository.selectById(11L)).thenReturn(leg);
        when(splitRepository.selectById("SPLIT-X")).thenReturn(split);
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant()));
        when(profitSharingService.refreshPendingReturn(any(), eq(leg))).thenReturn(false);
        when(profitSharingService.retryFailedReturnLegs(anyList(), anyMap())).thenReturn(0);

        scheduler.processTask(task);

        assertEquals("PENDING", task.getStatus());
        assertEquals(1, task.getRetryCount());
        assertTrue(task.getResult().contains("retry=1/5"));
        verify(taskRepository, times(2)).save(task);
    }

    /** R20-9 核心：两条腿各有独立任务，处理其中一条不得碰另一条。 */
    @Test
    void processTask_eachLegTask_onlyTouchesItsOwnLeg() {
        CompensationTask task = pendingReturnTask(22L);
        OrderSplitReturnLeg own = openLeg(22L, "PSR-2");
        OrderSplitReturnLeg sibling = openLeg(11L, "PSR-1");
        when(legRepository.selectById(22L)).thenReturn(own, confirmedLeg(own));
        when(splitRepository.selectById("SPLIT-X")).thenReturn(pendingSplit());
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant()));
        when(profitSharingService.refreshPendingReturn(any(), eq(own))).thenReturn(true);

        scheduler.processTask(task);

        verify(profitSharingService).refreshPendingReturn(any(), eq(own));
        verify(profitSharingService, never()).refreshPendingReturn(any(), eq(sibling));
        assertEquals("COMPLETED", task.getStatus());
    }

    /** 存量兼容：txId 还是裸 splitId 时，取该 split 最老一条开放腿跑完即止。 */
    @Test
    void processTask_legacySplitTxId_resolvesOldestOpenLeg() {
        CompensationTask task = pendingReturnTask(11L);
        task.setTxId("SPLIT-X");
        OrderSplitReturnLeg oldest = openLeg(11L, "PSR-1");
        OrderSplitReturnLeg newer = openLeg(22L, "PSR-2");
        when(legRepository.findOpenBySplitId("SPLIT-X")).thenReturn(List.of(oldest, newer));
        when(legRepository.selectById(11L)).thenReturn(oldest, confirmedLeg(oldest));
        when(splitRepository.selectById("SPLIT-X")).thenReturn(pendingSplit());
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant()));
        when(profitSharingService.refreshPendingReturn(any(), eq(oldest))).thenReturn(true);

        scheduler.processTask(task);

        verify(profitSharingService).refreshPendingReturn(any(), eq(oldest));
        verify(profitSharingService, never()).refreshPendingReturn(any(), eq(newer));
        assertEquals("COMPLETED", task.getStatus());
    }

    /** L2-3：未知任务类型防御性终态，不再尝试分布式事务补偿。 */
    @Test
    void processTask_unknownType_shouldFailDefensively() {
        CompensationTask task = pendingReturnTask(11L);
        task.setTaskType("SOME_LEGACY_TYPE");

        scheduler.processTask(task);

        assertEquals("FAILED", task.getStatus());
        assertTrue(task.getResult().contains("unsupported task type"));
    }

    private static CompensationTask pendingReturnTask(long legId) {
        CompensationTask task = new CompensationTask();
        task.setTaskId(99L);
        task.setTxId(ProfitSharingReturnLegService.legTxId(legId));
        task.setTaskType(ProfitSharingReturnCompensationService.TASK_TYPE);
        task.setStatus("PENDING");
        task.setRetryCount(0);
        return task;
    }

    private static OrderSplitReturnLeg openLeg(long legId, String outReturnNo) {
        OrderSplitReturnLeg leg = new OrderSplitReturnLeg();
        leg.setLegId(legId);
        leg.setSplitId("SPLIT-X");
        leg.setOutReturnNo(outReturnNo);
        leg.setCents(500L);
        leg.setStatus(ProfitSharingReturnLegService.STATUS_PROCESSING);
        return leg;
    }

    private static OrderSplitReturnLeg confirmedLeg(OrderSplitReturnLeg leg) {
        OrderSplitReturnLeg done = new OrderSplitReturnLeg();
        done.setLegId(leg.getLegId());
        done.setSplitId(leg.getSplitId());
        done.setOutReturnNo(leg.getOutReturnNo());
        done.setCents(leg.getCents());
        done.setStatus(ProfitSharingReturnLegService.STATUS_SUCCESS);
        return done;
    }

    private static OrderRevenueSplit pendingSplit() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-X");
        split.setMerchantId("M-1");
        split.setWechatPendingReturnNo("PSR-X");
        split.setWechatPendingReturnCents(500L);
        return split;
    }

    private static Merchant merchant() {
        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setWechatReceiverId("1900000109");
        return merchant;
    }
}
