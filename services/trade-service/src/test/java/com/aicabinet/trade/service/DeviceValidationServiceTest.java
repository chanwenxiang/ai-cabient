package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

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
}
