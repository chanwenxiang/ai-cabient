package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ReplenishmentTaskLine;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 补货＝自提自送：柜机库存只能来自**现场实收**，出库单上的领货量不得补记进柜机。
 *
 * <p>真实事故（2026-10-28 真机 任务 8）：商户只上架 2 个雪碧，任务行也只有 100013×2，
 * 但出库单上是 2/3/3（雪碧/冰红茶/加多宝）。旧实现把「出库量 − 任务行量」的差额当作已上架补记，
 * 于是冰红茶 +3、加多宝 +3 凭空进账，首页「库存偏低」从 2 变 0。
 *
 * <p>CB-074（2026-10-29）补上另一半：差额不但不许进柜机，还必须**留下台账**——签收按 SKU 传实收，
 * 未上满的量由 {@code WarehouseService} 写进入库行的 {@code remainder_qty}（领而未上），
 * 不再用 {@code Integer.MAX_VALUE} 把整单伪造成「全量签收」。
 */
@ExtendWith(MockitoExtension.class)
class ReplenishmentOutboundRemainderNotCreditedTest {

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

    private ReplenishmentTask task(long taskId, long outboundId) {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(taskId);
        task.setDeviceId("EG-DOOR");
        task.setOutboundId(outboundId);
        task.setStatus("IN_PROGRESS");
        task.setCheckInAt(Instant.parse("2026-10-28T10:00:00Z"));
        return task;
    }

    private ReplenishmentTaskLine restockLine(long taskId, String skuId, int quantity) {
        ReplenishmentTaskLine line = new ReplenishmentTaskLine();
        line.setLineId(900L);
        line.setTaskId(taskId);
        line.setLineType("RESTOCK");
        line.setSkuId(skuId);
        line.setQuantity(quantity);
        line.setApplied(false);
        return line;
    }

    @Test
    void completeTask_onlyMerchantReportedSkuIsCredited_outboundRemainderIsNot() {
        ReplenishmentTask task = task(50L, 500L);
        ReplenishmentTaskLine reported = restockLine(50L, "100013", 2);

        when(taskRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(task));
        when(taskLineRepository.findByTaskIdAndAppliedFalse(50L)).thenReturn(List.of(reported));
        // 完成链会重新读全量行取「实收」：同一个对象，apply 后已置 applied=true
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(50L)).thenReturn(List.of(reported));
        when(inTransitService.hasOpenForDevice(500L, "EG-DOOR")).thenReturn(true);
        when(deviceSlotService.isDoorCabinet("EG-DOOR")).thenReturn(true);
        when(deviceRepository.findById("EG-DOOR")).thenReturn(Optional.empty());
        when(taskRepository.save(any(ReplenishmentTask.class))).thenAnswer(inv -> inv.getArgument(0));

        replenishmentService.completeTask(1L, 50L);

        // 只按现场实收入账
        verify(inventoryLotService).applyReplenishmentLine(
                eq("EG-DOOR"), eq(reported), eq(1L), eq("50"));
        // 关键判据：不再去读/补记出库单差额（旧实现会给冰红茶、加多宝各 +3）
        verify(warehouseService, never()).outboundLinesForDevice(anyLong(), anyString());
        verifyNoMoreInteractions(inventoryLotService);
        // 在途照结（货确实出了仓），交接按 SKU 记实收：只报 100013×2 ⇒ 未覆盖的出库行落差额（CB-074）
        verify(inTransitService).receiveForDevice(500L, "EG-DOOR");
        verify(warehouseService).markDeviceHandoverReceived(500L, "EG-DOOR", Map.of("100013", 2));
    }

    @Test
    void completeTask_allReportedQuantitiesZero_stillReportsEmptyReceiptNotWholeOutbound() {
        // 铁律 80：现场没上的货要把行数量减到 0。若按「实收合计>0」判有没有明细，
        // 全 0 会掉进整单入柜分支，把如实登记的 0 反转成柜机虚增。
        ReplenishmentTask task = task(52L, 502L);
        ReplenishmentTaskLine zeroed = restockLine(52L, "100013", 0);

        when(taskRepository.findByIdForUpdate(52L)).thenReturn(Optional.of(task));
        when(taskLineRepository.findByTaskIdAndAppliedFalse(52L)).thenReturn(List.of(zeroed));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(52L)).thenReturn(List.of(zeroed));
        when(inTransitService.hasOpenForDevice(502L, "EG-DOOR")).thenReturn(true);
        when(deviceSlotService.isDoorCabinet("EG-DOOR")).thenReturn(true);
        when(deviceRepository.findById("EG-DOOR")).thenReturn(Optional.empty());
        when(taskRepository.save(any(ReplenishmentTask.class))).thenAnswer(inv -> inv.getArgument(0));

        replenishmentService.completeTask(1L, 52L);

        // 如实登记的 0 仍要「应用」（走现场行通道，加 0 件），但绝不得回退去按出库行入账
        verify(inventoryLotService).applyReplenishmentLine(eq("EG-DOOR"), eq(zeroed), eq(1L), eq("52"));
        verify(inventoryLotService, never()).addRestock(anyString(), anyString(), any());
        verify(warehouseService, never()).outboundLinesForDevice(anyLong(), anyString());
        verify(warehouseService).markDeviceHandoverReceived(502L, "EG-DOOR", Map.of());
        verify(warehouseService, never()).markDeviceHandoverReceivedFull(anyLong(), anyString());
    }

    @Test
    void completeTask_noReportedLine_fallsBackToOutboundAsBefore() {
        // 建单即完成等无现场明细场景：仍按出库行整单入柜（保留既有行为，避免一刀切丢账）
        ReplenishmentTask task = task(51L, 501L);
        com.aicabinet.trade.domain.WarehouseOutboundLine ol =
                new com.aicabinet.trade.domain.WarehouseOutboundLine();
        ol.setSkuId("SKU-COLA");
        ol.setBatchNo("B-1");
        ol.setQuantity(4);

        when(taskRepository.findByIdForUpdate(51L)).thenReturn(Optional.of(task));
        when(taskLineRepository.findByTaskIdAndAppliedFalse(51L)).thenReturn(List.of());
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(51L)).thenReturn(List.of());
        when(inTransitService.hasOpenForDevice(501L, "EG-DOOR")).thenReturn(true);
        when(warehouseService.outboundLinesForDevice(501L, "EG-DOOR")).thenReturn(List.of(ol));
        when(deviceSlotService.isDoorCabinet("EG-DOOR")).thenReturn(true);
        when(deviceRepository.findById("EG-DOOR")).thenReturn(Optional.empty());
        when(taskRepository.save(any(ReplenishmentTask.class))).thenAnswer(inv -> inv.getArgument(0));

        replenishmentService.completeTask(1L, 51L);

        verify(inventoryLotService).addRestock(eq("EG-DOOR"), eq("SKU-COLA"),
                any(InventoryLotService.RestockCommand.class));
        verify(inTransitService).receiveForDevice(501L, "EG-DOOR");
        verify(warehouseService).markDeviceHandoverReceivedFull(501L, "EG-DOOR");
    }
}
