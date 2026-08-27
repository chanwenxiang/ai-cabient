package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.DeviceEnvReadingMapper;
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
class DeviceEnvConcurrencyTest {

    @Mock private DeviceEnvReadingMapper readingRepository;
    @Mock private DistributedLockService distributedLockService;

    private DeviceEnvService service;

    @BeforeEach
    void setUp() {
        service = new DeviceEnvService(readingRepository, distributedLockService);
    }

    @Test
    void record_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(DeviceEnvService.deviceEnvLockKey("CAB-001")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.saveReading("CAB-001", 45.0, null, null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void record_whenLockAcquired_unlocksAfterInsert() {
        when(distributedLockService.tryLock(
                eq(DeviceEnvService.deviceEnvLockKey("CAB-002")), eq(60L), eq(5L)))
                .thenReturn(true);

        service.saveReading("CAB-002", 50.0, null, null);

        verify(distributedLockService).unlock(DeviceEnvService.deviceEnvLockKey("CAB-002"));
        verify(readingRepository).insert(org.mockito.ArgumentMatchers.any());
    }
}
