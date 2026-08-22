package com.aicabinet.trade.service;

import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceSalesLockConcurrencyTest {

    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private DeviceServiceClient deviceClient;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private DeviceSalesLockService service;

    @BeforeEach
    void setUp() {
        service = new DeviceSalesLockService(deviceRepository, deviceClient, auditService, distributedLockService);
    }

    @Test
    void applySalesLock_whenLockBusy_rejectsWithConflict() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("DEV-LOCK-1");
        when(distributedLockService.tryLock(
                eq(DeviceSalesLockService.deviceSalesLockKey("DEV-LOCK-1")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.applySalesLock(1L, device, true, "维修", false));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void applySalesLock_whenDeviceNotFound_unlocksLock() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("DEV-LOCK-2");
        when(distributedLockService.tryLock(
                eq(DeviceSalesLockService.deviceSalesLockKey("DEV-LOCK-2")), eq(60L), eq(5L)))
                .thenReturn(true);
        when(deviceRepository.findByIdForUpdate("DEV-LOCK-2")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.applySalesLock(1L, device, true, "维修", false));

        verify(distributedLockService).unlock(DeviceSalesLockService.deviceSalesLockKey("DEV-LOCK-2"));
    }
}
