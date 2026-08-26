package com.aicabinet.trade.service;

import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
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
class OpsServiceConcurrencyTest {

    @Mock private SessionService sessionService;
    @Mock private DeviceValidationService deviceValidationService;
    @Mock private DeviceServiceClient deviceClient;
    @Mock private ShoppingSessionMapper sessionRepository;
    @Mock private ReplenishmentTaskMapper taskRepository;
    @Mock private DistributedLockService distributedLockService;

    private OpsService service;

    @BeforeEach
    void setUp() {
        service = new OpsService(sessionService, deviceValidationService, deviceClient,
                sessionRepository, taskRepository, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void openDoorForRestockAsUser_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(ReplenishmentService.replenishmentTaskLockKey(9L)), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.openDoorForRestockAsUser(1L, "DEV-1", 9L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void openDoorForRestockAsUser_whenTaskMissing_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(ReplenishmentService.replenishmentTaskLockKey(10L)), eq(60L), eq(5L)))
                .thenReturn(true);
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(10L);
        task.setStatus("PENDING");
        when(deviceValidationService.ensureRestockDoorAllowed("DEV-2", 10L, 1L)).thenReturn(task);
        when(taskRepository.findByIdForUpdate(10L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.openDoorForRestockAsUser(1L, "DEV-2", 10L));

        verify(distributedLockService).unlock(ReplenishmentService.replenishmentTaskLockKey(10L));
    }
}
