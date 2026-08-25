package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.*;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplenishmentRouteConcurrencyTest {

    @Mock private ReplenishmentTaskMapper taskRepository;
    @Mock private ReplenishmentRouteMapper routeRepository;
    @Mock private DistributedLockService distributedLockService;

    private ReplenishmentService service;

    @BeforeEach
    void setUp() {
        service = new ReplenishmentService(
                null, routeRepository, taskRepository, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, distributedLockService);
    }

    @Test
    void cancelEmptyRoute_whenRouteLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(ReplenishmentService.replenishmentRouteLockKey(5L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.cancelEmptyRoute(1L, 5L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
