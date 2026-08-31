package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.DeviceInfoMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceIdServiceTest {

    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private DistributedLockService distributedLockService;

    @Test
    void peekNextNumericDeviceId_startsAtMinimumWhenEmpty() {
        when(deviceRepository.maxNumericDeviceIdRaw()).thenReturn(null);
        DeviceIdService service = new DeviceIdService(deviceRepository, distributedLockService);
        assertEquals("100001", service.peekNextNumericDeviceId());
    }

    @Test
    void resolveForCreate_blankAllocatesWithLock() {
        when(distributedLockService.tryLock("device:id:allocate", 30L, 5L)).thenReturn(true);
        when(deviceRepository.maxNumericDeviceIdRaw()).thenReturn(100_005L);
        when(deviceRepository.selectById("100006")).thenReturn(null);

        DeviceIdService service = new DeviceIdService(deviceRepository, distributedLockService);
        assertEquals("100006", service.resolveForCreate(null));
        verify(distributedLockService).unlock("device:id:allocate");
    }

    @Test
    void resolveForCreate_rejectsNonNumeric() {
        DeviceIdService service = new DeviceIdService(deviceRepository, distributedLockService);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.resolveForCreate("CAB-001"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void resolveForCreate_acceptsNumericInput() {
        DeviceIdService service = new DeviceIdService(deviceRepository, distributedLockService);
        assertEquals("100099", service.resolveForCreate("100099"));
    }
}
