package com.aicabinet.trade.service;

import com.aicabinet.common.dto.WarehouseOutboundDto;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自提自送口径（2026-10-28 用户拍板）下的「确认上架」闭环。
 *
 * <p>缺陷成因：那轮把建单链路改成「建出库单后立即种现场明细」，却没有同步改完成门禁。
 * 门禁仍要求 {@code warehouse_in_transit} 有在途行，而无发运环节后没人在补货前点发运，
 * 于是出库单恒为 DRAFT ⇒ 商户端「确认全部上架」被 409 拦住（任务根本无法完成），
 * 仓库存也永不扣减。真机 2026-10-28 17:46 实测复现（仅提示「出库单尚未发运在途」）。
 *
 * <p>本门禁钉住修好后的三条行为：
 * ① DRAFT/未发运 ⇒ 完成时补一次拣货+发运（扣仓库存），随后按现场实收的 SKU 量成对签收（CB-074）；
 * ② 已作废出库单 ⇒ 不补发运（仓库存已回仓），按任务行独立上架；
 * ③ 已有在途 ⇒ 不重复发运（防双扣仓库存）。
 */
@ExtendWith(MockitoExtension.class)
class ReplenishmentSelfPickupShipTest {

    private static final String DEVICE = "CAB-001";
    private static final Long TASK_ID = 50L;
    private static final Long OUTBOUND_ID = 500L;
    private static final Long OPERATOR = 1L;

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
        lenient().when(taskRepository.save(any(ReplenishmentTask.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(inventoryRepository.findByIdDeviceId(anyString())).thenReturn(List.of());
        lenient().when(deviceRepository.findById(anyString())).thenReturn(Optional.empty());
        lenient().when(deviceSlotService.isDoorCabinet(anyString())).thenReturn(true);
    }

    @Test
    void completeTask_unshippedOutbound_shipsThenReceives() {
        ReplenishmentTask task = openTask();
        ReplenishmentTaskLine pending = restockLine(1L, 2);

        when(taskRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
        when(taskLineRepository.findByTaskIdAndAppliedFalse(TASK_ID)).thenReturn(List.of(pending));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(TASK_ID)).thenReturn(List.of(pending));
        when(warehouseService.hasOutboundLinesForDevice(OUTBOUND_ID, DEVICE)).thenReturn(true);
        when(warehouseService.getOutbound(OUTBOUND_ID)).thenReturn(outbound("DRAFT"));
        // 第一次校验=发运前（无在途），第二次=补发运后的落库自检
        when(inTransitService.hasOpenForDevice(OUTBOUND_ID, DEVICE)).thenReturn(false, true);

        replenishmentService.completeTask(OPERATOR, TASK_ID);

        verify(warehouseService).markPicked(OUTBOUND_ID);
        verify(warehouseService).shipOutbound(OPERATOR, OUTBOUND_ID);
        // CB-074：签收按现场实收的 SKU 量，不拿出库量冒充，也不整单伪造全量
        verify(warehouseService).markDeviceHandoverReceived(OUTBOUND_ID, DEVICE, Map.of("SKU-COLA", 2));
        assertEquals("COMPLETED", task.getStatus());
    }

    @Test
    void completeTask_cancelledOutbound_skipsShipping() {
        ReplenishmentTask task = openTask();
        ReplenishmentTaskLine pending = restockLine(2L, 2);

        when(taskRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
        when(taskLineRepository.findByTaskIdAndAppliedFalse(TASK_ID)).thenReturn(List.of(pending));
        when(warehouseService.hasOutboundLinesForDevice(OUTBOUND_ID, DEVICE)).thenReturn(true);
        when(warehouseService.getOutbound(OUTBOUND_ID)).thenReturn(outbound("CANCELLED"));
        when(inTransitService.hasOpenForDevice(OUTBOUND_ID, DEVICE)).thenReturn(false);

        replenishmentService.completeTask(OPERATOR, TASK_ID);

        // 已作废 ⇒ 仓库存已回仓，绝不能再发运一次；任务行独立上架即可
        verify(warehouseService, never()).markPicked(anyLong());
        verify(warehouseService, never()).shipOutbound(anyLong(), anyLong());
        assertEquals("COMPLETED", task.getStatus());
    }

    @Test
    void completeTask_alreadyInTransit_doesNotShipAgain() {
        ReplenishmentTask task = openTask();
        ReplenishmentTaskLine pending = restockLine(3L, 2);

        when(taskRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
        when(taskLineRepository.findByTaskIdAndAppliedFalse(TASK_ID)).thenReturn(List.of(pending));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(TASK_ID)).thenReturn(List.of(pending));
        when(inTransitService.hasOpenForDevice(OUTBOUND_ID, DEVICE)).thenReturn(true);

        replenishmentService.completeTask(OPERATOR, TASK_ID);

        // 已有在途 ⇒ 走签收，不得重复发运（否则仓库存被扣两次）
        verify(warehouseService, never()).markPicked(anyLong());
        verify(warehouseService, never()).shipOutbound(anyLong(), anyLong());
        verify(warehouseService).markDeviceHandoverReceived(OUTBOUND_ID, DEVICE, Map.of("SKU-COLA", 2));
        assertEquals("COMPLETED", task.getStatus());
    }

    private ReplenishmentTask openTask() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(TASK_ID);
        task.setDeviceId(DEVICE);
        task.setOutboundId(OUTBOUND_ID);
        task.setStatus("IN_PROGRESS");
        task.setCheckInAt(Instant.parse("2026-10-28T09:00:00Z"));
        return task;
    }

    private ReplenishmentTaskLine restockLine(Long lineId, int qty) {
        ReplenishmentTaskLine line = new ReplenishmentTaskLine();
        line.setLineId(lineId);
        line.setTaskId(TASK_ID);
        line.setLineType("RESTOCK");
        line.setSkuId("SKU-COLA");
        line.setQuantity(qty);
        line.setApplied(false);
        return line;
    }

    private WarehouseOutboundDto outbound(String status) {
        return new WarehouseOutboundDto(OUTBOUND_ID, "WH-DEMO-001", 900L, status,
                OPERATOR, null, Instant.parse("2026-10-28T07:00:00Z"), null, List.of());
    }
}
