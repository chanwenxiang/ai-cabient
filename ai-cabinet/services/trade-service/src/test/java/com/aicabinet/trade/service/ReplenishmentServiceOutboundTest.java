package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ReplenishmentTaskLine;
import com.aicabinet.trade.domain.WarehouseOutboundLine;
import com.aicabinet.trade.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReplenishmentServiceOutboundTest {

    @Mock
    private ReplenishmentTaskRepository taskRepository;
    @Mock
    private ReplenishmentTaskLineRepository taskLineRepository;
    @Mock
    private WarehouseService warehouseService;
    @Mock
    private DeviceSlotService deviceSlotService;

    private ReplenishmentService replenishmentService;

    @BeforeEach
    void setUp() {
        replenishmentService = new ReplenishmentService(
                null, null, taskRepository, taskLineRepository, null, null, null, null,
                new ObjectMapper(), warehouseService, null, deviceSlotService, null);
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
}
