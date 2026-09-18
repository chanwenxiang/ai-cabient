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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 签到**状态闸**的负向证明（2026-09-18 新增）。
 *
 * <p>为什么单独立一个类：这是「终态不可复活」这条不变量的守门测试。
 * 旧行为里 {@code doCheckInTask} 只挡「有无可履约明细」，对 {@code CANCELLED} 会把它改回
 * {@code IN_PROGRESS}；而冻结判据是 {@code status=IN_PROGRESS 且 checkInAt≠null}，
 * 「取消空任务」又正是运营用来解冻柜机的手段 ⇒ **一次签到即可撤销运营刚解除的冻结**。
 *
 * <p>所以这里不只断言「抛了 409」，还断言**没有落库**（{@code save} 从未被调用）——
 * 否则「抛异常之前已经改了状态」这种半途生效照样能骗过状态码断言。
 *
 * <p>判据与开放（{@code DeviceValidationService.ensureRestockDoorAllowed}）共用
 * {@link ApiMessages#REPLENISHMENT_TASK_FINISHED} + 同一状态集合，改一处必须同步另一处。
 */
@ExtendWith(MockitoExtension.class)
class ReplenishmentCheckInStatusGatesTest {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_CANCELLED = "CANCELLED";

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

    /**
     * 注意用 lenient：终态用例会在「读明细行」之前就被状态闸拦下，
     * 于是这两个 stub 根本不会被触发 —— 而 Mockito 严格模式会把它当成「多余 stub」报错。
     * 那个报错本身其实是**好消息**（证明闸门确实排在前面），但不该让用例红。
     */
    private ReplenishmentTask taskWithLines(long taskId, String status) {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(taskId);
        task.setDeviceId("CAB-1");
        task.setStatus(status);
        lenient().when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        lenient()
                .when(taskLineRepository.findByTaskIdOrderByLineIdAsc(taskId))
                .thenReturn(List.of(new ReplenishmentTaskLine()));
        return task;
    }

    private void deviceWithCoords() {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-1");
        device.setLatitude(31.2304);
        device.setLongitude(121.4737);
        lenient().when(deviceRepository.findById("CAB-1")).thenReturn(Optional.of(device));
    }

    @Test
    void checkIn_rejectsCancelledTask_andDoesNotResurrectIt() {
        ReplenishmentTask task = taskWithLines(21L, STATUS_CANCELLED);
        deviceWithCoords();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> replenishmentService.checkInTask(1L, 21L,
                        new ReplenishmentCheckInRequest(31.2304, 121.4737)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(ApiMessages.REPLENISHMENT_TASK_FINISHED, ex.getReason());
        // 关键：不允许「先改了状态再抛异常」。save 从未发生 ⇒ 柜机不会被重新冻结。
        verify(taskRepository, never()).save(any());
        assertEquals(STATUS_CANCELLED, task.getStatus());
        assertNull(task.getCheckInAt());
    }

    @Test
    void checkIn_rejectsCompletedTask_withoutOverwritingCheckInEvidence() {
        ReplenishmentTask task = taskWithLines(22L, STATUS_COMPLETED);
        deviceWithCoords();
        var originalCheckInAt = java.time.Instant.parse("2026-09-01T02:00:00Z");
        task.setCheckInAt(originalCheckInAt);
        task.setCheckInLat(31.2304);
        task.setCheckInLng(121.4737);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> replenishmentService.checkInTask(1L, 22L,
                        new ReplenishmentCheckInRequest(31.3304, 121.4737)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(ApiMessages.REPLENISHMENT_TASK_FINISHED, ex.getReason());
        verify(taskRepository, never()).save(any());
        // 旧行为会用本次请求的坐标**覆盖**已完成任务的签到证据，这里的判据是「原值必须原地不动」。
        assertEquals(originalCheckInAt, task.getCheckInAt());
        assertEquals(31.2304, task.getCheckInLat());
        assertEquals(STATUS_COMPLETED, task.getStatus());
    }

    /**
     * 闸门**顺序**证明：终态闸必须在坐标闸之前。
     * 用「已取消 + 柜机无坐标」构造两闸都会命中的输入 —— 若报的是
     * {@code DEVICE_LOCATION_MISSING}，说明终态闸被挪到了后面（或删了），
     * 现场会收到「去补录坐标」这种**做了也没用**的指引。
     */
    @Test
    void checkIn_terminalStatusGateRunsBeforeLocationGates() {
        taskWithLines(23L, STATUS_CANCELLED);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-1");
        // lenient 是**刻意的**：本用例的前提就是「device 根本不会被查到」——
        // 若哪天这个 stub 被消费了（Mockito 会报 unnecessary-stubbing 消失），
        // 说明终态闸不再最先命中，这个用例就在提示闸门顺序被改过了。
        lenient().when(deviceRepository.findById("CAB-1")).thenReturn(Optional.of(device));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> replenishmentService.checkInTask(1L, 23L,
                        new ReplenishmentCheckInRequest(31.2304, 121.4737)));

        assertEquals(ApiMessages.REPLENISHMENT_TASK_FINISHED, ex.getReason());
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void checkIn_stillWorksForPendingTask() {
        ReplenishmentTask task = taskWithLines(24L, STATUS_PENDING);
        deviceWithCoords();
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var dto = replenishmentService.checkInTask(1L, 24L,
                new ReplenishmentCheckInRequest(31.2304, 121.4737));

        assertNotNull(dto.checkInAt());
        assertEquals(STATUS_IN_PROGRESS, task.getStatus());
        // 同批新增：DTO 必须回填 deviceHasCoords，客户端据此做前置拦
        assertEquals(Boolean.TRUE, dto.deviceHasCoords());
    }

    @Test
    void checkIn_worksForInProgressTask_keepsStatus() {
        ReplenishmentTask task = taskWithLines(25L, STATUS_IN_PROGRESS);
        deviceWithCoords();
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var dto = replenishmentService.checkInTask(1L, 25L,
                new ReplenishmentCheckInRequest(31.2304, 121.4737));

        assertNotNull(dto.checkInAt());
        assertEquals(STATUS_IN_PROGRESS, task.getStatus());
    }

    /**
     * 补货机柜漏填坐标时必须让客户端**看得出**这件事（deviceHasCoords=false），
     * 否则现场只能靠 400 事后告知。注意：本用例的柜机无坐标 ⇒ 签到本身仍然被拒，
     * 这才是 fail-closed 的正确形状。
     */
    @Test
    void checkIn_reportsDeviceHasCoordsFalseWhenDeviceHasNoCoords() {
        taskWithLines(26L, STATUS_PENDING);
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-1");
        when(deviceRepository.findById("CAB-1")).thenReturn(Optional.of(device));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> replenishmentService.checkInTask(1L, 26L,
                        new ReplenishmentCheckInRequest(31.2304, 121.4737)));
        assertEquals(ApiMessages.REPLENISHMENT_CHECK_IN_DEVICE_LOCATION_MISSING, ex.getReason());
    }
}
