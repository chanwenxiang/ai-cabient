package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ReplenishmentCheckInRequest;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ReplenishmentTaskLine;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.mapper.ReplenishmentRouteMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskLineMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplenishmentCheckInLocationGatesTest {

    @Mock private ReplenishmentRouteMapper routeRepository;
    @Mock private ReplenishmentTaskMapper taskRepository;
    @Mock private ReplenishmentTaskLineMapper taskLineRepository;
    @Mock private PullOffTaskMapper pullOffTaskRepository;
    @Mock private WarehouseService warehouseService;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private DeviceSlotService deviceSlotService;
    @Mock private InTransitService inTransitService;
    @Mock private SessionService sessionService;
    @Mock private NotificationService notificationService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private SystemConfigService systemConfigService;

    private ReplenishmentService replenishmentService;

    @BeforeEach
    void setUp() {
        replenishmentService = new ReplenishmentService(
                null, routeRepository, taskRepository, taskLineRepository, null, null, null, pullOffTaskRepository,
                new ObjectMapper(), warehouseService, deviceRepository, deviceSlotService, inTransitService,
                sessionService, null, null, notificationService, distributedLockService, systemConfigService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(replenishmentService, "self", replenishmentService);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        lenient().when(systemConfigService.getBoolean(
                SystemConfigService.REPLENISHMENT_CHECK_IN_REQUIRE_LOCATION, true)).thenReturn(true);
        lenient().when(systemConfigService.getInt(
                SystemConfigService.REPLENISHMENT_CHECK_IN_MAX_DISTANCE_M, 500)).thenReturn(500);
    }

    private ReplenishmentTask taskWithLines(long taskId) {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(taskId);
        task.setDeviceId("CAB-1");
        task.setStatus("PLANNED");
        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(taskId))
                .thenReturn(List.of(new ReplenishmentTaskLine()));
        return task;
    }

    private DeviceInfo deviceWithCoords() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-1");
        device.setLatitude(31.2304);
        device.setLongitude(121.4737);
        when(deviceRepository.findById("CAB-1")).thenReturn(Optional.of(device));
        return device;
    }

    @Test
    void checkIn_rejectsEmptyLocationWhenDeviceHasCoords() {
        taskWithLines(11L);
        deviceWithCoords();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> replenishmentService.checkInTask(1L, 11L, new ReplenishmentCheckInRequest(null, null)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(ApiMessages.REPLENISHMENT_CHECK_IN_LOCATION_REQUIRED, ex.getReason());
    }

    @Test
    void checkIn_allowsEmptyLocationWhenConfigDisabled() {
        taskWithLines(12L);
        deviceWithCoords();
        when(systemConfigService.getBoolean(
                SystemConfigService.REPLENISHMENT_CHECK_IN_REQUIRE_LOCATION, true)).thenReturn(false);
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var dto = replenishmentService.checkInTask(1L, 12L, new ReplenishmentCheckInRequest(null, null));
        assertNotNull(dto.checkInAt());
    }

    @Test
    void checkIn_rejectsTooFarWhenWithinDefaultRadiusOff() {
        taskWithLines(13L);
        deviceWithCoords();
        // ~11km north of Shanghai coords
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> replenishmentService.checkInTask(1L, 13L,
                        new ReplenishmentCheckInRequest(31.3304, 121.4737)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertNotNull(ex.getReason());
        org.junit.jupiter.api.Assertions.assertTrue(ex.getReason().contains("超出 500 米"));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getReason().startsWith("签到位置距柜机约"));
    }

    @Test
    void checkIn_skipsDistanceWhenMaxDistanceDisabled() {
        taskWithLines(14L);
        deviceWithCoords();
        when(systemConfigService.getInt(
                SystemConfigService.REPLENISHMENT_CHECK_IN_MAX_DISTANCE_M, 500)).thenReturn(0);
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var dto = replenishmentService.checkInTask(1L, 14L,
                new ReplenishmentCheckInRequest(31.3304, 121.4737));
        assertNotNull(dto.checkInAt());
        assertEquals(31.3304, dto.checkInLat());
    }
}
