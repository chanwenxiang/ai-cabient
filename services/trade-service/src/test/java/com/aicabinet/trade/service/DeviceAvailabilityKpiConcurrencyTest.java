package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.DeviceAvailabilityKpiDailyMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceAvailabilityKpiConcurrencyTest {

    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private OpsExceptionMapper exceptionRepository;
    @Mock private AdminAuditLogMapper auditRepository;
    @Mock private DeviceAvailabilityKpiDailyMapper kpiRepository;
    @Mock private DistributedLockService distributedLockService;

    private DeviceAvailabilityKpiService service;

    @BeforeEach
    void setUp() {
        service = new DeviceAvailabilityKpiService(deviceRepository, exceptionRepository,
                auditRepository, kpiRepository, distributedLockService);
    }

    @Test
    void snapshotDaily_whenLockBusy_returnsExistingWithoutWrite() {
        LocalDate date = LocalDate.parse("2026-08-18");
        when(distributedLockService.tryLock(
                eq(DeviceAvailabilityKpiService.deviceKpiDailyLockKey(date)), eq(60L), eq(5L)))
                .thenReturn(false);

        service.snapshotDaily(date);

        verify(kpiRepository).selectById(date);
    }
}
