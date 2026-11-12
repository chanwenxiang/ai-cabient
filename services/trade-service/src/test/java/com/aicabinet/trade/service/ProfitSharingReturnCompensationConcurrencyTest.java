package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.domain.OrderSplitReturnLeg;
import com.aicabinet.trade.mapper.CompensationTaskMapper;
import com.aicabinet.trade.mapper.OrderSplitReturnLegMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfitSharingReturnCompensationConcurrencyTest {

    @Mock private CompensationTaskMapper taskRepository;
    @Mock private DistributedLockService distributedLockService;
    @Mock private OrderSplitReturnLegMapper legRepository;

    private ProfitSharingReturnCompensationService service;

    @BeforeEach
    void setUp() {
        service = new ProfitSharingReturnCompensationService(
                taskRepository, distributedLockService, legRepository);
    }

    @Test
    void scheduleReturnRetry_whenLockBusy_skipsInsert() {
        OrderRevenueSplit split = split("SPLIT-1");
        when(legRepository.findOpenBySplitId("SPLIT-1")).thenReturn(List.of(leg(1L, "SPLIT-1")));
        when(distributedLockService.tryLock(
                ProfitSharingReturnCompensationService.returnCompensationLockKey("SPLIT-1"), 60L, 5L))
                .thenReturn(false);

        service.scheduleReturnRetry(split, 60);

        verify(taskRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void scheduleReturnRetry_whenLockAcquired_checksPendingAndSaves() {
        OrderRevenueSplit split = split("SPLIT-2");
        when(legRepository.findOpenBySplitId("SPLIT-2")).thenReturn(List.of(leg(2L, "SPLIT-2")));
        when(distributedLockService.tryLock(
                ProfitSharingReturnCompensationService.returnCompensationLockKey("SPLIT-2"), 60L, 5L))
                .thenReturn(true);
        when(taskRepository.existsPending("LEG:2", ProfitSharingReturnCompensationService.TASK_TYPE))
                .thenReturn(false);

        service.scheduleReturnRetry(split, 60);

        verify(taskRepository).save(org.mockito.ArgumentMatchers.any());
        verify(distributedLockService).unlock(
                ProfitSharingReturnCompensationService.returnCompensationLockKey("SPLIT-2"));
    }

    private static OrderRevenueSplit split(String splitId) {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId(splitId);
        return split;
    }

    private static OrderSplitReturnLeg leg(long legId, String splitId) {
        OrderSplitReturnLeg leg = new OrderSplitReturnLeg();
        leg.setLegId(legId);
        leg.setSplitId(splitId);
        leg.setOutReturnNo("RET-" + legId);
        leg.setCents(500L);
        leg.setStatus(ProfitSharingReturnLegService.STATUS_PROCESSING);
        return leg;
    }
}
