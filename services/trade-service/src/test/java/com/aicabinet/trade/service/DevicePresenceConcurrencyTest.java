package com.aicabinet.trade.service;

import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceTemperatureReadingMapper;
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
class DevicePresenceConcurrencyTest {

    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private DeviceTemperatureReadingMapper temperatureReadingRepository;
    @Mock private CabinetMetrics cabinetMetrics;
    @Mock private OpsExceptionService opsExceptionService;
    @Mock private SystemConfigService systemConfigService;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private DevicePresenceService service;

    @BeforeEach
    void setUp() {
        service = new DevicePresenceService(deviceRepository, temperatureReadingRepository,
                cabinetMetrics, opsExceptionService, systemConfigService, auditService,
                distributedLockService);
    }

    @Test
    void heartbeat_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(DevicePresenceService.devicePresenceLockKey("CAB-HB")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.heartbeat("CAB-HB"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
