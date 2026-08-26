package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceTempPlanEntryDto;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.DeviceTempPlan;
import com.aicabinet.trade.domain.DeviceTempPlanEntry;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceTempPlanEntryMapper;
import com.aicabinet.trade.mapper.DeviceTempPlanMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceTempPlanConcurrencyTest {

    @Mock private PermissionService permissionService;
    @Mock private DeviceTempPlanMapper planRepository;
    @Mock private DeviceTempPlanEntryMapper entryRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private DeviceServiceClient deviceClient;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private DeviceTempPlanService service;

    @BeforeEach
    void setUp() {
        service = new DeviceTempPlanService(permissionService, planRepository, entryRepository,
                deviceRepository, deviceClient, auditService, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void upsert_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(DeviceTempPlanService.tempPlanLockKey("CAB-001")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsert(1L, "CAB-001", true,
                        List.of(new DeviceTempPlanEntryDto(null, 0, 5))));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void applyNow_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(DeviceTempPlanService.tempPlanLockKey("CAB-002")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.applyNow("CAB-002"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void applyNowScheduler_whenDeviceMissing_unlocksLock() {
        DeviceTempPlan plan = new DeviceTempPlan();
        plan.setPlanId(10L);
        plan.setDeviceId("CAB-003");
        plan.setEnabled(true);
        when(distributedLockService.tryLock(
                eq(DeviceTempPlanService.tempPlanLockKey("CAB-003")), eq(60L), eq(5L)))
                .thenReturn(true);
        when(planRepository.findByDeviceId("CAB-003")).thenReturn(Optional.of(plan));
        when(entryRepository.findByPlanId(10L)).thenReturn(List.of(entry(1L, 0, 5)));
        when(deviceRepository.findByIdForUpdate("CAB-003")).thenReturn(Optional.empty());

        service.applyNow("CAB-003");

        verify(distributedLockService).unlock(DeviceTempPlanService.tempPlanLockKey("CAB-003"));
    }

    private static DeviceTempPlanEntry entry(Long id, int start, int target) {
        DeviceTempPlanEntry e = new DeviceTempPlanEntry();
        e.setEntryId(id);
        e.setPlanId(10L);
        e.setStartMinute(start);
        e.setTargetTempC(target);
        return e;
    }
}
