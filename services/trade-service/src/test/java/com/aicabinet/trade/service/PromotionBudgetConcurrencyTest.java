package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.PromotionActivityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionBudgetConcurrencyTest {

    @Mock private PromotionActivityMapper repository;
    @Mock private DistributedLockService distributedLockService;

    private PromotionService promotionService;

    @BeforeEach
    void setUp() {
        promotionService = new PromotionService(repository, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(promotionService, "self", promotionService);
    }

    @Test
    void reserveBudget_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(PromotionService.promotionActivityLockKey(5L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> promotionService.reserveBudgetOnClaim(5L, 100));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void releaseBudget_whenActivityMissing_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(PromotionService.promotionActivityLockKey(5L)), eq(60L), eq(5L)))
                .thenReturn(true);
        when(repository.findByIdForUpdate(5L)).thenReturn(java.util.Optional.empty());

        promotionService.releaseBudget(5L, 100);

        verify(distributedLockService).unlock(PromotionService.promotionActivityLockKey(5L));
    }
}
