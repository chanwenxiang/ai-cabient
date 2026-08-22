package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.CompensationTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfitSharingReturnCompensationConcurrencyTest {

    @Mock private CompensationTaskMapper taskRepository;
    @Mock private DistributedLockService distributedLockService;

    private ProfitSharingReturnCompensationService service;

    @BeforeEach
    void setUp() {
        service = new ProfitSharingReturnCompensationService(taskRepository, distributedLockService);
    }

    @Test
    void scheduleReturnRetry_whenLockBusy_skipsInsert() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-1");
        split.setWechatPendingReturnNo("RET-1");
        when(distributedLockService.tryLock(
                eq(ProfitSharingReturnCompensationService.returnCompensationLockKey("SPLIT-1")),
                eq(60L), eq(5L)))
                .thenReturn(false);

        service.scheduleReturnRetry(split, 60);

        verify(taskRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void scheduleReturnRetry_whenLockAcquired_checksPendingAndSaves() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-2");
        split.setWechatPendingReturnNo("RET-2");
        when(distributedLockService.tryLock(
                eq(ProfitSharingReturnCompensationService.returnCompensationLockKey("SPLIT-2")),
                eq(60L), eq(5L)))
                .thenReturn(true);
        when(taskRepository.existsPending("SPLIT-2", ProfitSharingReturnCompensationService.TASK_TYPE))
                .thenReturn(false);

        service.scheduleReturnRetry(split, 60);

        verify(taskRepository).save(org.mockito.ArgumentMatchers.any());
        verify(distributedLockService).unlock(
                ProfitSharingReturnCompensationService.returnCompensationLockKey("SPLIT-2"));
    }
}
