package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CompensationTask;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.CompensationTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfitSharingReturnCompensationServiceTest {

    @Mock private CompensationTaskMapper taskRepository;
    @Mock private DistributedLockService distributedLockService;

    private ProfitSharingReturnCompensationService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(distributedLockService.tryLock(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        service = new ProfitSharingReturnCompensationService(taskRepository, distributedLockService);
    }

    @Test
    void scheduleReturnRetry_shouldPersistPendingTask() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-1");
        split.setWechatPendingReturnNo("PSR-1");
        when(taskRepository.existsPending("SPLIT-1", ProfitSharingReturnCompensationService.TASK_TYPE))
                .thenReturn(false);

        service.scheduleReturnRetry(split, 60);

        ArgumentCaptor<CompensationTask> captor = ArgumentCaptor.forClass(CompensationTask.class);
        verify(taskRepository).save(captor.capture());
        CompensationTask task = captor.getValue();
        assertEquals("SPLIT-1", task.getTxId());
        assertEquals(ProfitSharingReturnCompensationService.TASK_TYPE, task.getTaskType());
        assertEquals("PENDING", task.getStatus());
    }

    @Test
    void scheduleReturnRetry_shouldSkipWhenPendingExists() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-2");
        split.setWechatPendingReturnNo("PSR-2");
        when(taskRepository.existsPending("SPLIT-2", ProfitSharingReturnCompensationService.TASK_TYPE))
                .thenReturn(true);

        service.scheduleReturnRetry(split, 60);

        verify(taskRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
