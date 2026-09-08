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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BUG-010：空任务签到冻柜与 cancel-empty 收口。
 */
@ExtendWith(MockitoExtension.class)
class ReplenishmentServiceEmptyTaskTest {

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

    private ReplenishmentService replenishmentService;

    @BeforeEach
    void setUp() {
        replenishmentService = new ReplenishmentService(
                null, routeRepository, taskRepository, taskLineRepository, null, null, null, pullOffTaskRepository,
                new ObjectMapper(), warehouseService, deviceRepository, deviceSlotService, inTransitService,
                sessionService, null, null, notificationService, distributedLockService, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(replenishmentService, "self", replenishmentService);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
    }

    @Test
    void checkInTask_shouldReject_whenNoLinesAndNoOutboundDetail() {
        ReplenishmentTask task = baseTask(7L);
        task.setOutboundId(101L);
        task.setStatus("IN_PROGRESS");
        when(taskRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(7L)).thenReturn(List.of());
        when(warehouseService.hasOutboundLinesForDevice(101L, "CAB-1")).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> replenishmentService.checkInTask(1L, 7L, new ReplenishmentCheckInRequest(null, null)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(ApiMessages.REPLENISHMENT_CHECK_IN_NO_LINES, ex.getReason());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void checkInTask_shouldAllow_whenTaskHasLines() {
        ReplenishmentTask task = baseTask(8L);
        task.setStatus("PLANNED");
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-1");
        when(taskRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(task));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(8L)).thenReturn(List.of(new ReplenishmentTaskLine()));
        when(deviceRepository.findById("CAB-1")).thenReturn(Optional.of(device));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(8L)).thenReturn(List.of(new ReplenishmentTaskLine()));

        var dto = replenishmentService.checkInTask(1L, 8L, new ReplenishmentCheckInRequest(null, null));

        assertNotNull(dto.checkInAt());
        assertEquals("IN_PROGRESS", dto.status());
        verify(taskRepository, times(1)).save(argThat(t -> t.getCheckInAt() != null));
    }

    @Test
    void cancelEmptyTask_shouldAllow_whenCheckedInButNothingApplied() {
        ReplenishmentTask task = baseTask(9L);
        task.setStatus("IN_PROGRESS");
        task.setCheckInAt(Instant.now());
        when(taskRepository.findById(9L)).thenReturn(Optional.of(task));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(9L)).thenReturn(List.of());
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var dto = replenishmentService.cancelEmptyTask(1L, 9L);

        assertEquals("CANCELLED", dto.status());
        verify(sessionService).closeRestockSessionsForTask(eq(9L), anyString());
    }

    @Test
    void cancelEmptyTask_shouldReject_whenLineAlreadyApplied() {
        ReplenishmentTask task = baseTask(10L);
        task.setStatus("IN_PROGRESS");
        task.setCheckInAt(Instant.now());
        ReplenishmentTaskLine applied = new ReplenishmentTaskLine();
        applied.setApplied(true);
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(10L)).thenReturn(List.of(applied));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> replenishmentService.cancelEmptyTask(1L, 10L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(ApiMessages.REPLENISHMENT_CANCEL_NOT_EMPTY, ex.getReason());
        verify(taskRepository, never()).save(any());
    }

    private static ReplenishmentTask baseTask(long id) {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(id);
        task.setDeviceId("CAB-1");
        task.setRouteId(1L);
        return task;
    }
}
