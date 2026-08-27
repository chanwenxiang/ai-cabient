package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceTempPlanDto;
import com.aicabinet.common.dto.DeviceTempPlanEntryDto;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.DeviceInfo;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceTempPlanServiceTest {

    private static final long OPERATOR_ID = 1900000001L;

    @Mock private PermissionService permissionService;
    @Mock private DeviceTempPlanMapper planRepository;
    @Mock private DeviceTempPlanEntryMapper entryRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private DeviceServiceClient deviceClient;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private ScheduledTaskService taskService;

    private DeviceTempPlanService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(distributedLockService.tryLock(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        service = new DeviceTempPlanService(permissionService, planRepository, entryRepository,
                deviceRepository, deviceClient, auditService, distributedLockService, null, taskService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void targetForMinute_shouldPickLastEntryAtOrBeforeMinuteWithWrap() {
        List<DeviceTempPlanEntryDto> entries = List.of(
                new DeviceTempPlanEntryDto(1L, 0, 5),
                new DeviceTempPlanEntryDto(2L, 480, 4),
                new DeviceTempPlanEntryDto(3L, 720, 3),
                new DeviceTempPlanEntryDto(4L, 1080, 2));

        assertEquals(5, DeviceTempPlanService.targetForMinute(entries, 60));
        assertEquals(4, DeviceTempPlanService.targetForMinute(entries, 600));
        assertEquals(3, DeviceTempPlanService.targetForMinute(entries, 900));
        assertEquals(2, DeviceTempPlanService.targetForMinute(entries, 1300));
        // 00:01 命中 00:00 条目
        assertEquals(5, DeviceTempPlanService.targetForMinute(entries, 1));
        // 无 00:00 条目的排程：00:01 跨日回绕取当日最后一条
        List<DeviceTempPlanEntryDto> noMidnight = List.of(
                new DeviceTempPlanEntryDto(2L, 480, 4),
                new DeviceTempPlanEntryDto(3L, 720, 3),
                new DeviceTempPlanEntryDto(4L, 1080, 2));
        assertEquals(2, DeviceTempPlanService.targetForMinute(noMidnight, 60));
    }

    @Test
    void applyNow_shouldPushCommandWhenTargetChanged() {
        DeviceTempPlan plan = new DeviceTempPlan();
        plan.setPlanId(10L);
        plan.setDeviceId("CAB-001");
        plan.setEnabled(true);
        when(planRepository.findByDeviceId("CAB-001")).thenReturn(Optional.of(plan));
        when(entryRepository.findByPlanId(10L)).thenReturn(List.of(entry(1L, 0, 4)));
        DeviceInfo device = device("CAB-001", "ONLINE", null);
        when(deviceRepository.findByIdForUpdate("CAB-001")).thenReturn(Optional.of(device));
        when(deviceClient.requestSetTargetTemp("CAB-001", 4)).thenReturn("CMD-1");

        service.applyNow("CAB-001");

        assertEquals(Integer.valueOf(4), device.getTargetTempC());
        verify(deviceRepository).save(device);
        verify(deviceClient).requestSetTargetTemp("CAB-001", 4);
    }

    @Test
    void applyNow_shouldSkipCommandWhenTargetUnchanged() {
        DeviceTempPlan plan = new DeviceTempPlan();
        plan.setPlanId(10L);
        plan.setDeviceId("CAB-001");
        plan.setEnabled(true);
        when(planRepository.findByDeviceId("CAB-001")).thenReturn(Optional.of(plan));
        when(entryRepository.findByPlanId(10L)).thenReturn(List.of(entry(1L, 0, 4)));
        DeviceInfo device = device("CAB-001", "ONLINE", 4);
        when(deviceRepository.findByIdForUpdate("CAB-001")).thenReturn(Optional.of(device));

        service.applyNow("CAB-001");

        verify(deviceClient, never()).requestSetTargetTemp(anyString(), anyInt());
    }

    @Test
    void upsert_shouldRejectEmptyOrDuplicateOrOutOfRange() {
        when(deviceRepository.findById("CAB-001")).thenReturn(Optional.of(device("CAB-001", "ONLINE", null)));

        assertThrows(ResponseStatusException.class,
                () -> service.upsert(OPERATOR_ID, "CAB-001", true, List.of()));
        assertThrows(ResponseStatusException.class,
                () -> service.upsert(OPERATOR_ID, "CAB-001", true, List.of(
                        new DeviceTempPlanEntryDto(null, 60, 5),
                        new DeviceTempPlanEntryDto(null, 60, 4))));
        assertThrows(ResponseStatusException.class,
                () -> service.upsert(OPERATOR_ID, "CAB-001", true, List.of(
                        new DeviceTempPlanEntryDto(null, 60, 99))));
        assertThrows(ResponseStatusException.class,
                () -> service.upsert(OPERATOR_ID, "CAB-001", true, List.of(
                        new DeviceTempPlanEntryDto(null, 1500, 5))));
    }

    @Test
    void get_shouldReturnEmptyPlanWhenAbsent() {
        when(deviceRepository.findById("CAB-001")).thenReturn(Optional.of(device("CAB-001", "ONLINE", null)));
        when(planRepository.findByDeviceId("CAB-001")).thenReturn(Optional.empty());

        DeviceTempPlanDto dto = service.get(OPERATOR_ID, "CAB-001");

        assertEquals("CAB-001", dto.deviceId());
        assertEquals(0, dto.entries().size());
    }

    private static DeviceTempPlanEntry entry(Long id, int start, int target) {
        DeviceTempPlanEntry e = new DeviceTempPlanEntry();
        e.setEntryId(id);
        e.setPlanId(10L);
        e.setStartMinute(start);
        e.setTargetTempC(target);
        return e;
    }

    private static DeviceInfo device(String id, String status, Integer targetTemp) {
        DeviceInfo d = new DeviceInfo();
        d.setDeviceId(id);
        d.setOnlineStatus(status);
        d.setTargetTempC(targetTemp);
        return d;
    }
}
