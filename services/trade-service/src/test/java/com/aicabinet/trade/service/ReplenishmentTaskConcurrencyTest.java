package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.ReplenishmentRouteMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskLineMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class ReplenishmentTaskConcurrencyTest {

    @Mock private ReplenishmentRouteMapper routeRepository;
    @Mock private ReplenishmentTaskMapper taskRepository;
    @Mock private ReplenishmentTaskLineMapper taskLineRepository;
    @Mock private DistributedLockService distributedLockService;

    private ReplenishmentService service;

    @BeforeEach
    void setUp() {
        service = new ReplenishmentService(
                null, routeRepository, taskRepository, taskLineRepository, null, null, null, null,
                new ObjectMapper(), null, null, null, null, null, null, null, null,
                distributedLockService);
    }

    @Test
    void completeTask_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(ReplenishmentService.replenishmentTaskLockKey(9L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.completeTask(1L, 9L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void completeTask_whenTaskNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(ReplenishmentService.replenishmentTaskLockKey(9L)), eq(60L), eq(5L)))
                .thenReturn(true);
        when(taskRepository.findByIdForUpdate(9L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.completeTask(1L, 9L));

        verify(distributedLockService).unlock(ReplenishmentService.replenishmentTaskLockKey(9L));
    }
}
