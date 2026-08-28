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
        replenishmentService = new ReplenishmentService(
                null, routeRepository, taskRepository, taskLineRepository, null, null, null, pullOffTaskRepository,
                new ObjectMapper(), warehouseService, null, deviceSlotService, inTransitService,
                sessionService, null, null, notificationService, distributedLockService, null);
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

    @Test
    void completeTask_rejectsOutboundTaskWhenNotInTransit() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(20L);
        task.setDeviceId("CAB-001");
        task.setOutboundId(200L);
        task.setStatus("IN_PROGRESS");
        task.setCheckInAt(java.time.Instant.parse("2026-07-24T06:00:00Z"));

        ReplenishmentTaskLine line = new ReplenishmentTaskLine();
        line.setTaskId(20L);
        line.setLineType("RESTOCK");
        line.setSkuId("SKU-DEMO-001");
        line.setQuantity(2);

        when(taskRepository.findByIdForUpdate(20L)).thenReturn(java.util.Optional.of(task));
        when(taskLineRepository.findByTaskIdAndAppliedFalse(20L)).thenReturn(List.of(line));
        when(inTransitService.hasOpenForDevice(200L, "CAB-001")).thenReturn(false);
        when(warehouseService.hasOutboundLinesForDevice(200L, "CAB-001")).thenReturn(true);

        assertThrows(ResponseStatusException.class,
                () -> replenishmentService.completeTask(100000001L, 20L));

        verify(taskLineRepository, never()).save(any());
        verify(warehouseService, never()).markDeviceHandoverReceived(anyLong(), anyString());
    }

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
