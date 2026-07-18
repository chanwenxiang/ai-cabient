package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceValidationServiceTest {

    @Mock
    private DeviceInfoMapper deviceInfoRepository;
    @Mock
    private ShoppingSessionMapper sessionRepository;
    @Mock
    private ReplenishmentTaskMapper replenishmentTaskRepository;

    private DeviceValidationService service;

    @BeforeEach
    void setUp() {
        service = new DeviceValidationService(deviceInfoRepository, sessionRepository, replenishmentTaskRepository);
    }

    @Test
    void ensureRestockDoorAllowed_requiresCheckIn() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(1L);
        task.setDeviceId("CAB-001");
        task.setStatus("PENDING");
        task.setAssigneeUserId(100000001L);
        when(replenishmentTaskRepository.findById(1L)).thenReturn(Optional.of(task));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.ensureRestockDoorAllowed("CAB-001", 1L, 100000001L));
        assertEquals(ApiMessages.REPLENISHMENT_CHECK_IN_REQUIRED, ex.getReason());
    }

    @Test
    void ensureRestockDoorAllowed_operatorCanBypassAssignee() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(1L);
        task.setDeviceId("CAB-001");
        task.setStatus("PENDING");
        task.setAssigneeUserId(1L);
        task.setCheckInAt(Instant.now());
        when(replenishmentTaskRepository.findById(1L)).thenReturn(Optional.of(task));
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-001");
        device.setOnlineStatus("ONLINE");
        when(deviceInfoRepository.findById("CAB-001")).thenReturn(Optional.of(device));
        when(sessionRepository.findByDeviceIdAndStateIn(ArgumentMatchers.eq("CAB-001"), ArgumentMatchers.anyList()))
                .thenReturn(List.of());

        assertSame(task, service.ensureRestockDoorAllowed("CAB-001", 1L, 100000001L));
    }

    @Test
    void ensureRestockDoorAllowed_merchantMustMatchAssignee() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(1L);
        task.setDeviceId("CAB-001");
        task.setStatus("PENDING");
        task.setAssigneeUserId(99L);
        when(replenishmentTaskRepository.findById(1L)).thenReturn(Optional.of(task));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.ensureRestockDoorAllowed("CAB-001", 1L, 42L));
        assertEquals(ApiMessages.REPLENISHMENT_TASK_ASSIGNEE, ex.getReason());
    }
}
