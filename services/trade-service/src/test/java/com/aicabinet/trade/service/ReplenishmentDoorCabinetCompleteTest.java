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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        // 任务无明细 ⇒ 整单入柜并按全量签收（CB-074 后「全量」是显式入口，不再传 MAX_VALUE）
        verify(warehouseService).markDeviceHandoverReceivedFull(400L, "EG-DOOR");
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

    @Test
    void listFulfillmentTasksPage_doorCabinetTaskNeverCountsAsPendingSlotAssignment() {
        // 审计 F3：开门柜补货行恒无货道，「待分配」徽章按旧判据会常亮、'仅待分配'筛选命中全部开门柜
        ReplenishmentTask door = fulfillmentTask(60L, "EG-DOOR");
        ReplenishmentTask shelf = fulfillmentTask(61L, "CAB-SHELF");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ReplenishmentTask> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        page.setRecords(List.of(door, shelf));
        when(taskRepository.searchPage(null, null, 0, 10)).thenReturn(page);
        when(taskLineRepository.selectMaps(any())).thenReturn(List.of(
                Map.of("task_id", 60L, "cnt", 2L),
                Map.of("task_id", 61L, "cnt", 3L)));
        when(deviceSlotService.isDoorCabinet("EG-DOOR")).thenReturn(true);
        when(deviceSlotService.isDoorCabinet("CAB-SHELF")).thenReturn(false);

        var out = replenishmentService.listFulfillmentTasksPage(null, null, 0, 10).items();

        assertEquals(0, badgeOf(out, 60L), "开门柜无货道概念，待分配恒 0");
        assertEquals(3, badgeOf(out, 61L), "货架柜照旧统计未分配货道的补货行");
    }

    @Test
    void listOpenPullOffTasksPage_doorCabinet_flagsCabinetAndSkipsSlotProbe() {
        // 审计 D26/待拍板第 3 项查出的真缺陷：开门柜无货道行 ⇒ totalHeadroomForSku 恒 0，
        // admin 效期 tab 据此禁用「补货任务」并弹「货道已满」，而后端 preparePullOffTaskPlan
        // 对开门柜明确不设货道余量闸 ⇒ 前端锁死了后端支持的操作。判据必须由服务端单源下发。
        com.aicabinet.trade.domain.PullOffTask door = pullOff(70L, "EG-DOOR");
        com.aicabinet.trade.domain.PullOffTask shelf = pullOff(71L, "CAB-SHELF");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.aicabinet.trade.domain.PullOffTask> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        page.setRecords(List.of(door, shelf));
        page.setTotal(2);

        when(pullOffTaskRepository.searchPage("OPEN", null, 0, 20)).thenReturn(page);
        when(deviceSlotService.isDoorCabinet("EG-DOOR")).thenReturn(true);
        when(deviceSlotService.isDoorCabinet("CAB-SHELF")).thenReturn(false);
        when(deviceSlotService.totalHeadroomForSku("CAB-SHELF", "SKU-COLA")).thenReturn(0);

        var items = replenishmentService.listOpenPullOffTasksPage(null, 0, 20).items();

        org.junit.jupiter.api.Assertions.assertTrue(pullOffById(items, 70L).doorCabinet(),
                "开门柜必须带柜型标志，否则前端只能按货道余量假锁补货按钮");
        org.junit.jupiter.api.Assertions.assertFalse(pullOffById(items, 71L).doorCabinet(),
                "货道柜照旧按货道余量门控");
        // 短路即判据：开门柜不该再去探一轮货道（每行一次空查询 + 一条误导性的 no slot headroom 日志）
        verify(deviceSlotService, never()).totalHeadroomForSku(eq("EG-DOOR"), anyString());
    }

    private static com.aicabinet.trade.domain.PullOffTask pullOff(long taskId, String deviceId) {
        com.aicabinet.trade.domain.PullOffTask task = new com.aicabinet.trade.domain.PullOffTask();
        task.setTaskId(taskId);
        task.setDeviceId(deviceId);
        task.setSkuId("SKU-COLA");
        task.setQuantity(2);
        task.setStatus("OPEN");
        return task;
    }

    private static com.aicabinet.common.dto.PullOffTaskDto pullOffById(
            List<com.aicabinet.common.dto.PullOffTaskDto> items, long taskId) {
        return items.stream().filter(d -> Long.valueOf(taskId).equals(d.taskId()))
                .findFirst().orElseThrow();
    }

    private static ReplenishmentTask fulfillmentTask(long taskId, String deviceId) {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(taskId);
        task.setDeviceId(deviceId);
        task.setStatus("PLANNED");
        return task;
    }

    private static int badgeOf(
            List<com.aicabinet.common.dto.ReplenishmentFulfillmentTaskDto> items, long taskId) {
        return items.stream().filter(d -> Long.valueOf(taskId).equals(d.taskId()))
                .findFirst().orElseThrow().unassignedRestockLines();
    }
}
