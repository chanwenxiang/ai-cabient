package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CompensationTask;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.domain.OrderSplitReturnLeg;
import com.aicabinet.trade.mapper.CompensationTaskMapper;
import com.aicabinet.trade.mapper.OrderSplitReturnLegMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R20-9：补偿任务按腿排。改前以 splitId 作 txId + existsPending 去重，
 * 多轮部分退的第二轮连调度槽都被吞；这里钉住「一条腿一个键」。
 */
@ExtendWith(MockitoExtension.class)
class ProfitSharingReturnCompensationServiceTest {

    @Mock private CompensationTaskMapper taskRepository;
    @Mock private DistributedLockService distributedLockService;
    @Mock private OrderSplitReturnLegMapper legRepository;

    private ProfitSharingReturnCompensationService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(distributedLockService.tryLock(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        service = new ProfitSharingReturnCompensationService(
                taskRepository, distributedLockService, legRepository);
    }

    @Test
    void scheduleReturnRetry_shouldPersistPendingTaskKeyedByLeg() {
        OrderRevenueSplit split = split("SPLIT-1");
        OrderSplitReturnLeg leg = leg(11L, "SPLIT-1", "PSR-1");
        when(legRepository.findOpenBySplitId("SPLIT-1")).thenReturn(List.of(leg));
        when(taskRepository.existsPending("LEG:11", ProfitSharingReturnCompensationService.TASK_TYPE))
                .thenReturn(false);

        service.scheduleReturnRetry(split, 60);

        ArgumentCaptor<CompensationTask> captor = ArgumentCaptor.forClass(CompensationTask.class);
        verify(taskRepository).save(captor.capture());
        CompensationTask task = captor.getValue();
        assertEquals("LEG:11", task.getTxId());
        assertEquals(ProfitSharingReturnCompensationService.TASK_TYPE, task.getTaskType());
        assertEquals("PENDING", task.getStatus());
    }

    @Test
    void scheduleReturnRetry_shouldSkipWhenPendingExistsForThatLeg() {
        OrderRevenueSplit split = split("SPLIT-2");
        OrderSplitReturnLeg leg = leg(22L, "SPLIT-2", "PSR-2");
        when(legRepository.findOpenBySplitId("SPLIT-2")).thenReturn(List.of(leg));
        when(taskRepository.existsPending("LEG:22", ProfitSharingReturnCompensationService.TASK_TYPE))
                .thenReturn(true);

        service.scheduleReturnRetry(split, 60);

        verify(taskRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    /** 核心判据：两条开放腿各排一个任务，不再被同一个 splitId 的去重吞掉。 */
    @Test
    void scheduleReturnRetry_multipleLegs_getIndependentTasks() {
        OrderRevenueSplit split = split("SPLIT-3");
        OrderSplitReturnLeg first = leg(31L, "SPLIT-3", "PSR-3-a");
        OrderSplitReturnLeg second = leg(32L, "SPLIT-3", "PSR-3-b");
        // 三参数入口直接吃腿列表，不再按 split 回查 ⇒ 这里不能 stub findOpenBySplitId。
        when(taskRepository.existsPending("LEG:31", ProfitSharingReturnCompensationService.TASK_TYPE))
                .thenReturn(false);
        when(taskRepository.existsPending("LEG:32", ProfitSharingReturnCompensationService.TASK_TYPE))
                .thenReturn(false);

        service.scheduleReturnRetry(split, List.of(first, second), 30);

        ArgumentCaptor<CompensationTask> captor = ArgumentCaptor.forClass(CompensationTask.class);
        verify(taskRepository, times(2)).save(captor.capture());
        assertEquals(List.of("LEG:31", "LEG:32"),
                captor.getAllValues().stream().map(CompensationTask::getTxId).toList());
    }

    private static OrderRevenueSplit split(String splitId) {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId(splitId);
        return split;
    }

    private static OrderSplitReturnLeg leg(long legId, String splitId, String outReturnNo) {
        OrderSplitReturnLeg leg = new OrderSplitReturnLeg();
        leg.setLegId(legId);
        leg.setSplitId(splitId);
        leg.setOutReturnNo(outReturnNo);
        leg.setCents(500L);
        leg.setStatus(ProfitSharingReturnLegService.STATUS_PROCESSING);
        return leg;
    }
}
