package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.WarehouseOutboundLine;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.mapper.ReplenishmentRouteMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskLineMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开门柜完成链：柜上残留货道时（hasSkuSlots 仍为真）也不能跳过「无货道加库存」，
 * 否则补货完成却不加任何库存（静默丢账）。判据=将邑绑定（2026-10-28 拍板）。
 */
@ExtendWith(MockitoExtension.class)
class ReplenishmentDoorCabinetCompleteTest {

    @Mock private DeviceSkuInventoryMapper inventoryRepository;
    @Mock private ReplenishmentRouteMapper routeRepository;
    @Mock private ReplenishmentTaskMapper taskRepository;
    @Mock private ReplenishmentTaskLineMapper taskLineRepository;
    @Mock private InventoryLotService inventoryLotService;
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
                inventoryRepository, routeRepository, taskRepository, taskLineRepository, null,
                inventoryLotService, null, pullOffTaskRepository, new ObjectMapper(), warehouseService,
                deviceRepository, deviceSlotService, inTransitService, sessionService, notificationService,
                distributedLockService, null, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(replenishmentService, "self", replenishmentService);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
    }

    @Test
    void completeTask_doorCabinetWithResidualSlots_stillAddsStockFromOutbound() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(40L);
        task.setDeviceId("EG-DOOR");
        task.setOutboundId(400L);
        task.setStatus("IN_PROGRESS");
        task.setCheckInAt(Instant.parse("2026-10-28T02:00:00Z"));

        WarehouseOutboundLine ol = new WarehouseOutboundLine();
        ol.setSkuId("SKU-COLA");
        ol.setBatchNo("B-2026-10-01");
        ol.setQuantity(5);
        ol.setExpiryDate(LocalDate.of(2027, 4, 1));

        when(taskRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(task));
        when(taskLineRepository.findByTaskIdAndAppliedFalse(40L)).thenReturn(List.of());
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(40L)).thenReturn(List.of());
        when(inTransitService.hasOpenForDevice(400L, "EG-DOOR")).thenReturn(true);
        // 有在途即走签收，不再查出库明细（自提自送补齐发运的短路顺序）
        when(warehouseService.outboundLinesForDevice(400L, "EG-DOOR")).thenReturn(List.of(ol));
        when(deviceSlotService.isDoorCabinet("EG-DOOR")).thenReturn(true);
        when(deviceRepository.findById("EG-DOOR")).thenReturn(Optional.empty());
        when(taskRepository.save(any(ReplenishmentTask.class))).thenAnswer(inv -> inv.getArgument(0));

        replenishmentService.completeTask(1L, 40L);

        verify(inventoryLotService).addRestock(eq("EG-DOOR"), eq("SKU-COLA"),
                any(InventoryLotService.RestockCommand.class));
        verify(warehouseService).markDeviceHandoverReceived(400L, "EG-DOOR", 5);
        // 判据短路：不探货道（柜上有残留货道也照样加库存）
        verify(deviceSlotService, never()).hasSkuSlots(anyString(), anyString());
        verify(deviceSlotService, never()).recordRestock(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void completeTask_doorCabinet_clearsResidualSlotOnHistoricalLine() {
        // 历史行残留货道号：内存归一给 apply 用，DB 用显式 SQL 清（updateById 跳过 null）
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(41L);
        task.setDeviceId("EG-DOOR");
        task.setStatus("IN_PROGRESS");
        task.setCheckInAt(Instant.parse("2026-10-28T03:00:00Z"));

        com.aicabinet.trade.domain.ReplenishmentTaskLine line =
                new com.aicabinet.trade.domain.ReplenishmentTaskLine();
        line.setLineId(5L);
        line.setTaskId(41L);
        line.setLineType("RESTOCK");
        line.setSkuId("SKU-COLA");
        line.setQuantity(2);
        line.setSlotId("B3");
        line.setApplied(false);

        when(taskRepository.findByIdForUpdate(41L)).thenReturn(Optional.of(task));
        when(taskLineRepository.findByTaskIdAndAppliedFalse(41L)).thenReturn(java.util.List.of(line));
        when(deviceSlotService.isDoorCabinet("EG-DOOR")).thenReturn(true);
        when(deviceRepository.findById("EG-DOOR")).thenReturn(Optional.empty());
        when(taskRepository.save(any(ReplenishmentTask.class))).thenAnswer(inv -> inv.getArgument(0));

        replenishmentService.completeTask(1L, 41L);

        verify(taskLineRepository).clearSlotId(5L);
        org.mockito.Mockito.verify(inventoryLotService)
                .applyReplenishmentLine(eq("EG-DOOR"), org.mockito.ArgumentMatchers.argThat(
                        l -> l.getSlotId() == null), eq(1L), eq("41"));
        verify(deviceSlotService).recordRestock("EG-DOOR", null, false);
        org.junit.jupiter.api.Assertions.assertTrue(line.isApplied());
        org.junit.jupiter.api.Assertions.assertNull(line.getSlotId());
    }
}
