package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CreateFromExpiryRequest;
import com.aicabinet.trade.domain.PullOffTask;
import com.aicabinet.trade.domain.ReplenishmentRoute;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ReplenishmentTaskLine;
import com.aicabinet.trade.domain.WarehouseOutboundLine;
import com.aicabinet.trade.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReplenishmentServiceOutboundTest {

    @Mock
    private ReplenishmentRouteMapper routeRepository;
    @Mock
    private ReplenishmentTaskMapper taskRepository;
    @Mock
    private ReplenishmentTaskLineMapper taskLineRepository;
    @Mock
    private PullOffTaskMapper pullOffTaskRepository;
    @Mock
    private WarehouseService warehouseService;
    @Mock
    private DeviceSlotService deviceSlotService;
    @Mock
    private InTransitService inTransitService;
    @Mock
    private SessionService sessionService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private DistributedLockService distributedLockService;

    private ReplenishmentService replenishmentService;

    @BeforeEach
    void setUp() {
        // MA-103：服务改成行锁读 findByIdForUpdate；Mockito 不会把接口 default 方法委托给真实实现，
        // 不显式 stub 就会返回空 Optional ⇒ 全部用例变成「临期告警不存在」404。这里代理回 findById，
        // 保留各用例原有的 findById(77L) 打桩不动。
        lenient().when(pullOffTaskRepository.findByIdForUpdate(any()))
                .thenAnswer(inv -> pullOffTaskRepository.findById(inv.getArgument(0)));
        replenishmentService = new ReplenishmentService(
                null, routeRepository, taskRepository, taskLineRepository, null, null, null, pullOffTaskRepository,
                new ObjectMapper(), warehouseService, null, deviceSlotService, inTransitService,
                sessionService, notificationService, distributedLockService, null, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(replenishmentService, "self", replenishmentService);
        lenient().when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
    }

    @Test
    void generateLinesFromOutbound_createsRestockLinesWithBatchAndSlot() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(10L);
        task.setDeviceId("CAB-001");
        task.setStatus("PLANNED");

        WarehouseOutboundLine outboundLine = new WarehouseOutboundLine();
        outboundLine.setSkuId("SKU-DEMO-001");
        outboundLine.setBatchNo("B20260701-001");
        outboundLine.setQuantity(6);
        outboundLine.setExpiryDate(LocalDate.of(2026, 12, 31));

        when(taskRepository.findByOutboundId(99L)).thenReturn(List.of(task));
        when(taskLineRepository.findByTaskIdAndAppliedFalse(10L)).thenReturn(List.of());
        when(warehouseService.outboundLinesForDevice(99L, "CAB-001")).thenReturn(List.of(outboundLine));
        when(deviceSlotService.allocateRestockQuantity("CAB-001", "SKU-DEMO-001", 6))
                .thenReturn(List.of(new DeviceSlotService.SlotRestockAllocation("A1", 6)));

        replenishmentService.generateLinesFromOutbound(99L);

        ArgumentCaptor<ReplenishmentTaskLine> lineCaptor = ArgumentCaptor.forClass(ReplenishmentTaskLine.class);
        verify(taskLineRepository).save(lineCaptor.capture());
        ReplenishmentTaskLine saved = lineCaptor.getValue();
        assertEquals("RESTOCK", saved.getLineType());
        assertEquals("SKU-DEMO-001", saved.getSkuId());
        assertEquals("B20260701-001", saved.getBatchNo());
        assertEquals(6, saved.getQuantity());
        assertEquals("A1", saved.getSlotId());
        assertFalse(saved.isApplied());

        ArgumentCaptor<ReplenishmentTask> taskCaptor = ArgumentCaptor.forClass(ReplenishmentTask.class);
        verify(taskRepository).save(taskCaptor.capture());
        assertEquals("IN_PROGRESS", taskCaptor.getValue().getStatus());
    }

    @Test
    void generateLinesFromOutbound_splitsAcrossMultipleSlots() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(12L);
        task.setDeviceId("CAB-001");
        task.setStatus("PLANNED");

        WarehouseOutboundLine outboundLine = new WarehouseOutboundLine();
        outboundLine.setSkuId("SKU-DEMO-001");
        outboundLine.setBatchNo("B20260701-002");
        outboundLine.setQuantity(10);
        outboundLine.setExpiryDate(LocalDate.of(2026, 12, 31));

        when(taskRepository.findByOutboundId(101L)).thenReturn(List.of(task));
        when(taskLineRepository.findByTaskIdAndAppliedFalse(12L)).thenReturn(List.of());
        when(warehouseService.outboundLinesForDevice(101L, "CAB-001")).thenReturn(List.of(outboundLine));
        when(deviceSlotService.allocateRestockQuantity("CAB-001", "SKU-DEMO-001", 10))
                .thenReturn(List.of(
                        new DeviceSlotService.SlotRestockAllocation("A1", 4),
                        new DeviceSlotService.SlotRestockAllocation("A2", 6)));

        replenishmentService.generateLinesFromOutbound(101L);

        ArgumentCaptor<ReplenishmentTaskLine> lineCaptor = ArgumentCaptor.forClass(ReplenishmentTaskLine.class);
        verify(taskLineRepository, times(2)).save(lineCaptor.capture());
        List<ReplenishmentTaskLine> saved = lineCaptor.getAllValues();
        assertEquals(10, saved.stream().mapToInt(ReplenishmentTaskLine::getQuantity).sum());
        assertTrue(saved.stream().anyMatch(l -> "A1".equals(l.getSlotId()) && l.getQuantity() == 4));
        assertTrue(saved.stream().anyMatch(l -> "A2".equals(l.getSlotId()) && l.getQuantity() == 6));
    }

    @Test
    void generateLinesFromOutbound_truncatesWhenOverSlotCapacity() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(13L);
        task.setDeviceId("CAB-001");
        task.setStatus("PLANNED");

        WarehouseOutboundLine outboundLine = new WarehouseOutboundLine();
        outboundLine.setSkuId("SKU-DEMO-001");
        outboundLine.setBatchNo("B20260701-003");
        outboundLine.setQuantity(16);
        outboundLine.setExpiryDate(LocalDate.of(2026, 12, 31));

        when(taskRepository.findByOutboundId(102L)).thenReturn(List.of(task));
        when(taskLineRepository.findByTaskIdAndAppliedFalse(13L)).thenReturn(List.of());
        when(warehouseService.outboundLinesForDevice(102L, "CAB-001")).thenReturn(List.of(outboundLine));
        when(deviceSlotService.allocateRestockQuantity("CAB-001", "SKU-DEMO-001", 16))
                .thenReturn(List.of(new DeviceSlotService.SlotRestockAllocation("A1", 6)));

        replenishmentService.generateLinesFromOutbound(102L);

        ArgumentCaptor<ReplenishmentTaskLine> lineCaptor = ArgumentCaptor.forClass(ReplenishmentTaskLine.class);
        verify(taskLineRepository, times(1)).save(lineCaptor.capture());
        ReplenishmentTaskLine saved = lineCaptor.getValue();
        assertEquals("A1", saved.getSlotId());
        assertEquals(6, saved.getQuantity());
    }

    @Test
    void generateLinesFromOutbound_skipsWhenManualLinesExist() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(11L);
        task.setDeviceId("CAB-001");
        task.setStatus("PLANNED");

        when(taskRepository.findByOutboundId(100L)).thenReturn(List.of(task));
        when(taskLineRepository.findByTaskIdAndAppliedFalse(11L))
                .thenReturn(List.of(new ReplenishmentTaskLine()));

        replenishmentService.generateLinesFromOutbound(100L);

        verify(taskLineRepository, never()).save(any());
        verify(warehouseService, never()).outboundLinesForDevice(anyLong(), anyString());
    }

    @Test
    void generateLinesFromOutbound_doorCabinet_createsSingleLineWithoutSlot() {
        // 开门柜（判据=将邑绑定）：出库行整行落无货道行，不探货道、不分配
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(30L);
        task.setDeviceId("CAB-DOOR");
        task.setStatus("PLANNED");

        WarehouseOutboundLine outboundLine = new WarehouseOutboundLine();
        outboundLine.setSkuId("SKU-DEMO-001");
        outboundLine.setBatchNo("B20260701-004");
        outboundLine.setQuantity(7);
        outboundLine.setExpiryDate(LocalDate.of(2026, 12, 31));

        when(taskRepository.findByOutboundId(103L)).thenReturn(List.of(task));
        when(taskLineRepository.findByTaskIdAndAppliedFalse(30L)).thenReturn(List.of());
        when(warehouseService.outboundLinesForDevice(103L, "CAB-DOOR")).thenReturn(List.of(outboundLine));
        when(deviceSlotService.isDoorCabinet("CAB-DOOR")).thenReturn(true);

        replenishmentService.generateLinesFromOutbound(103L);

        ArgumentCaptor<ReplenishmentTaskLine> lineCaptor = ArgumentCaptor.forClass(ReplenishmentTaskLine.class);
        verify(taskLineRepository).save(lineCaptor.capture());
        ReplenishmentTaskLine saved = lineCaptor.getValue();
        assertNull(saved.getSlotId());
        assertEquals(7, saved.getQuantity());
        assertEquals("RESTOCK", saved.getLineType());
        assertEquals("B20260701-004", saved.getBatchNo());
        verify(deviceSlotService, never()).allocateRestockQuantity(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(deviceSlotService, never()).hasSkuSlots(anyString(), anyString());
    }

    @Test
    void completeTask_rejectsWithoutCheckIn() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(21L);
        task.setDeviceId("CAB-001");
        task.setStatus("IN_PROGRESS");
        task.setCheckInAt(null);

        when(taskRepository.findByIdForUpdate(21L)).thenReturn(java.util.Optional.of(task));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> replenishmentService.completeTask(100000001L, 21L));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("签到"));

        verify(taskLineRepository, never()).findByTaskIdAndAppliedFalse(anyLong());
        verify(taskLineRepository, never()).save(any());
    }

    // 「未发运即拒绝完成」的旧行为已被推翻：自提自送口径下改为完成时自动补发运。
    // 对应的新门禁见 ReplenishmentSelfPickupShipTest（那里有完整依赖，能跑完整个完成链）。

    @Test
    void createTaskFromPullOff_restock_createsOneLinePerSlotAllocation() {
        PullOffTask pull = openPull(77L, "CAB-001", "SKU-DEMO-001", 5, "B-EXP");
        when(pullOffTaskRepository.findById(77L)).thenReturn(java.util.Optional.of(pull));
        when(deviceSlotService.hasSkuSlots("CAB-001", "SKU-DEMO-001")).thenReturn(true);
        when(deviceSlotService.totalHeadroomForSku("CAB-001", "SKU-DEMO-001")).thenReturn(10);
        when(deviceSlotService.allocateRestockQuantity("CAB-001", "SKU-DEMO-001", 5))
                .thenReturn(List.of(
                        new DeviceSlotService.SlotRestockAllocation("A1", 3),
                        new DeviceSlotService.SlotRestockAllocation("A2", 2)));
        when(routeRepository.save(any(ReplenishmentRoute.class))).thenAnswer(inv -> {
            ReplenishmentRoute r = inv.getArgument(0);
            r.setRouteId(900L);
            return r;
        });
        when(taskRepository.save(any(ReplenishmentTask.class))).thenAnswer(inv -> {
            ReplenishmentTask t = inv.getArgument(0);
            t.setTaskId(901L);
            return t;
        });
        when(taskRepository.findByRouteId(900L)).thenReturn(List.of());

        replenishmentService.createTaskFromPullOff(
                100000001L, 77L, new CreateFromExpiryRequest("RESTOCK", null));

        ArgumentCaptor<ReplenishmentTaskLine> lineCaptor = ArgumentCaptor.forClass(ReplenishmentTaskLine.class);
        verify(taskLineRepository, times(2)).save(lineCaptor.capture());
        List<ReplenishmentTaskLine> lines = lineCaptor.getAllValues();
        assertEquals(5, lines.stream().mapToInt(ReplenishmentTaskLine::getQuantity).sum());
        assertTrue(lines.stream().anyMatch(l -> "A1".equals(l.getSlotId()) && l.getQuantity() == 3));
        assertTrue(lines.stream().anyMatch(l -> "A2".equals(l.getSlotId()) && l.getQuantity() == 2));
        assertTrue(lines.stream().allMatch(l -> "RESTOCK".equals(l.getLineType())));
        assertEquals("RESOLVED", pull.getStatus());
        verify(pullOffTaskRepository).save(pull);
    }

    @Test
    void createTaskFromPullOff_restock_rejectsUnboundSku() {
        PullOffTask pull = openPull(78L, "CAB-001", "SKU-UNBOUND", 2, null);
        when(pullOffTaskRepository.findById(78L)).thenReturn(java.util.Optional.of(pull));
        when(deviceSlotService.hasSkuSlots("CAB-001", "SKU-UNBOUND")).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> replenishmentService.createTaskFromPullOff(
                        100000001L, 78L, new CreateFromExpiryRequest("RESTOCK", null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("未绑定货道"));
        verify(taskLineRepository, never()).save(any());
    }

    @Test
    void createTaskFromPullOff_restock_rejectsWhenHeadroomZero() {
        PullOffTask pull = openPull(79L, "CAB-001", "SKU-DEMO-001", 3, "B-FULL");
        when(pullOffTaskRepository.findById(79L)).thenReturn(java.util.Optional.of(pull));
        when(deviceSlotService.hasSkuSlots("CAB-001", "SKU-DEMO-001")).thenReturn(true);
        when(deviceSlotService.totalHeadroomForSku("CAB-001", "SKU-DEMO-001")).thenReturn(0);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> replenishmentService.createTaskFromPullOff(
                        100000001L, 79L, new CreateFromExpiryRequest("RESTOCK", null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("货道已满"));
        verify(taskLineRepository, never()).save(any());
        verify(routeRepository, never()).save(any());
    }

    @Test
    void createTaskFromPullOff_restock_rejectsWhenAllocEmpty() {
        PullOffTask pull = openPull(80L, "CAB-001", "SKU-DEMO-001", 2, null);
        when(pullOffTaskRepository.findById(80L)).thenReturn(java.util.Optional.of(pull));
        when(deviceSlotService.hasSkuSlots("CAB-001", "SKU-DEMO-001")).thenReturn(true);
        when(deviceSlotService.totalHeadroomForSku("CAB-001", "SKU-DEMO-001")).thenReturn(2);
        when(deviceSlotService.allocateRestockQuantity("CAB-001", "SKU-DEMO-001", 2))
                .thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> replenishmentService.createTaskFromPullOff(
                        100000001L, 80L, new CreateFromExpiryRequest("RESTOCK", null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("货道已满"));
        verify(taskLineRepository, never()).save(any());
    }

    @Test
    void createTaskFromPullOff_restock_doorCabinet_allowsWithoutSlotPlan() {
        // 开门柜（判据=将邑绑定）：不要求 SKU 绑货道、不受货道余量限幅，
        // 整行按告警数量建无货道 RESTOCK 行（旧逻辑会 400「未绑定货道」/「货道已满」）
        PullOffTask pull = openPull(81L, "CAB-DOOR", "SKU-DEMO-001", 4, "B-DOOR");
        when(pullOffTaskRepository.findById(81L)).thenReturn(java.util.Optional.of(pull));
        when(deviceSlotService.isDoorCabinet("CAB-DOOR")).thenReturn(true);
        when(routeRepository.save(any(ReplenishmentRoute.class))).thenAnswer(inv -> {
            ReplenishmentRoute r = inv.getArgument(0);
            r.setRouteId(901L);
            return r;
        });
        when(taskRepository.save(any(ReplenishmentTask.class))).thenAnswer(inv -> {
            ReplenishmentTask t = inv.getArgument(0);
            t.setTaskId(902L);
            return t;
        });
        when(taskRepository.findByRouteId(901L)).thenReturn(List.of());

        replenishmentService.createTaskFromPullOff(
                100000001L, 81L, new CreateFromExpiryRequest("RESTOCK", null));

        ArgumentCaptor<ReplenishmentTaskLine> lineCaptor = ArgumentCaptor.forClass(ReplenishmentTaskLine.class);
        verify(taskLineRepository).save(lineCaptor.capture());
        ReplenishmentTaskLine line = lineCaptor.getValue();
        assertNull(line.getSlotId());
        assertEquals(4, line.getQuantity());
        assertEquals("RESTOCK", line.getLineType());
        assertEquals("B-DOOR", line.getBatchNo());
        verify(deviceSlotService, never()).hasSkuSlots(anyString(), anyString());
        verify(deviceSlotService, never()).totalHeadroomForSku(anyString(), anyString());
        verify(deviceSlotService, never()).allocateRestockQuantity(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        assertEquals("RESOLVED", pull.getStatus());
    }

    private static PullOffTask openPull(long id, String deviceId, String skuId, int qty, String batchNo) {
        PullOffTask pull = new PullOffTask();
        try {
            var field = PullOffTask.class.getDeclaredField("taskId");
            field.setAccessible(true);
            field.set(pull, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        pull.setDeviceId(deviceId);
        pull.setSkuId(skuId);
        pull.setQuantity(qty);
        pull.setBatchNo(batchNo);
        pull.setStatus("OPEN");
        return pull;
    }
}
