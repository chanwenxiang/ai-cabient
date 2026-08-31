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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceIdServiceTest {

    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private DistributedLockService distributedLockService;

    @Test
    void resolveForCreate_rejectsManualInput() {
        DeviceIdService service = new DeviceIdService(deviceRepository, distributedLockService);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.resolveForCreate("100099"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void allocateRandomDeviceId_returnsTwelveDigits() {
        when(distributedLockService.tryLock("device:id:allocate", 30L, 5L)).thenReturn(true);
        when(deviceRepository.selectById(anyString())).thenReturn(null);

        DeviceIdService service = new DeviceIdService(deviceRepository, distributedLockService);
        String id = service.resolveForCreate(null);

        assertTrue(DeviceIdService.isStandardDeviceId(id));
        verify(distributedLockService).unlock("device:id:allocate");
    }
}
