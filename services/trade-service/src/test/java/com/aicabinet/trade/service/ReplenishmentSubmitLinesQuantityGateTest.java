package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ReplenishmentTaskLineDto;
import com.aicabinet.common.dto.SubmitReplenishmentLinesRequest;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计 P1-17（2026-10-15）：qty≤0 明确拒绝而非静默丢行——静默丢行会让商户以为
 * 提交成功但补货清单缺行（对不上的账）。前端有数量钳制，此为后端兜底防线。
 */
@ExtendWith(MockitoExtension.class)
class ReplenishmentSubmitLinesQuantityGateTest {

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
                sessionService, notificationService, distributedLockService, systemConfigService, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(replenishmentService, "self", replenishmentService);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
    }

    @Test
    void submitTaskLines_shouldRejectZeroQuantityRestockLine() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(11L);
        task.setDeviceId("CAB-1");
        task.setStatus("IN_PROGRESS");
        when(taskRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(task));

        ReplenishmentTaskLineDto dto = new ReplenishmentTaskLineDto(
                null, "RESTOCK", "SKU-COLA", "可乐", "B-01", null, null, 0, "A1", false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> replenishmentService.submitTaskLines(1L, 11L, new SubmitReplenishmentLinesRequest(List.of(dto))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(taskLineRepository, never()).save(any());
    }

    @Test
    void submitTaskLines_shouldAllowPositiveQuantityRestockLine() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(12L);
        task.setDeviceId("CAB-1");
        task.setRouteId(1L);
        task.setStatus("IN_PROGRESS");
        when(taskRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(task));
        when(taskRepository.findByRouteId(1L)).thenReturn(List.of(task));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(12L)).thenReturn(List.of());

        ReplenishmentTaskLineDto dto = new ReplenishmentTaskLineDto(
                null, "RESTOCK", "SKU-COLA", "可乐", "B-01", null, null, 3, "A1", false);

        // 正常正数量行不应被数量门禁拦截（走完提交链路不抛 BAD_REQUEST 即可）
        replenishmentService.submitTaskLines(1L, 12L, new SubmitReplenishmentLinesRequest(List.of(dto)));

        verify(taskLineRepository).save(any());
    }

    @Test
    void submitTaskLines_shouldRejectZeroQuantityPullOffLine() {
        // CB-042（2026-10-15）：0 件行不进任务清单——下架行与 RESTOCK 行同口径
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(13L);
        task.setDeviceId("CAB-1");
        task.setStatus("IN_PROGRESS");
        when(taskRepository.findByIdForUpdate(13L)).thenReturn(Optional.of(task));

        ReplenishmentTaskLineDto dto = new ReplenishmentTaskLineDto(
                null, "PULL_OFF", "SKU-COLA", "可乐", "B-01", null, null, 0, "A1", false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> replenishmentService.submitTaskLines(1L, 13L, new SubmitReplenishmentLinesRequest(List.of(dto))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(taskLineRepository, never()).save(any());
    }

    @Test
    void submitTaskLines_shouldRejectUnknownLineTypeAtSubmitTime() {
        // CB-042：未知 lineType 提交时拒绝——此前落库成功、到任务完成时才炸 unknown lineType
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(14L);
        task.setDeviceId("CAB-1");
        task.setStatus("IN_PROGRESS");
        when(taskRepository.findByIdForUpdate(14L)).thenReturn(Optional.of(task));

        ReplenishmentTaskLineDto dto = new ReplenishmentTaskLineDto(
                null, "INVENTORY", "SKU-COLA", "可乐", "B-01", null, null, 3, "A1", false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> replenishmentService.submitTaskLines(1L, 14L, new SubmitReplenishmentLinesRequest(List.of(dto))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(taskLineRepository, never()).save(any());
    }

    @Test
    void submitTaskLines_shouldAllowPositiveQuantityPullOffLine() {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(15L);
        task.setDeviceId("CAB-1");
        task.setRouteId(1L);
        task.setStatus("IN_PROGRESS");
        when(taskRepository.findByIdForUpdate(15L)).thenReturn(Optional.of(task));
        when(taskRepository.findByRouteId(1L)).thenReturn(List.of(task));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(15L)).thenReturn(List.of());

        ReplenishmentTaskLineDto dto = new ReplenishmentTaskLineDto(
                null, "PULL_OFF", "SKU-COLA", "可乐", "B-01", null, null, 2, "A1", false);

        replenishmentService.submitTaskLines(1L, 15L, new SubmitReplenishmentLinesRequest(List.of(dto)));

        verify(taskLineRepository).save(any());
    }

    @Test
    void submitTaskLines_zeroSlotDevice_savesRestockLineWithoutSlot() {
        // 开门柜没有格子（2026-10-27 EG943322621391）：该 SKU 无启用货道时不再 400
        // 「无可用货道容量」，整行落 slotId=null（旧草稿行的已删货道号一并归零）
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(16L);
        task.setDeviceId("CAB-DOOR");
        task.setRouteId(1L);
        task.setStatus("IN_PROGRESS");
        when(taskRepository.findByIdForUpdate(16L)).thenReturn(Optional.of(task));
        when(taskRepository.findByRouteId(1L)).thenReturn(List.of(task));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(16L)).thenReturn(List.of());
        when(deviceSlotService.hasSkuSlots("CAB-DOOR", "SKU-COLA")).thenReturn(false);

        ReplenishmentTaskLineDto dto = new ReplenishmentTaskLineDto(
                null, "RESTOCK", "SKU-COLA", "雪碧", "B-WH-SPRITE330-01", null, null, 2, "B3", false);

        replenishmentService.submitTaskLines(1L, 16L, new SubmitReplenishmentLinesRequest(List.of(dto)));

        org.mockito.ArgumentCaptor<com.aicabinet.trade.domain.ReplenishmentTaskLine> captor =
                org.mockito.ArgumentCaptor.forClass(com.aicabinet.trade.domain.ReplenishmentTaskLine.class);
        verify(taskLineRepository).save(captor.capture());
        org.junit.jupiter.api.Assertions.assertNull(captor.getValue().getSlotId());
        verify(deviceSlotService, never()).validateRestockLine(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(deviceSlotService, never()).allocateRestockQuantity(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void submitTaskLines_withSlots_stillAllocatesByHeadroom() {
        // 锁：有货道机型维持原分配路径（无货道豁免不外溢）
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(17L);
        task.setDeviceId("CAB-GRAVITY");
        task.setRouteId(1L);
        task.setStatus("IN_PROGRESS");
        when(taskRepository.findByIdForUpdate(17L)).thenReturn(Optional.of(task));
        when(taskRepository.findByRouteId(1L)).thenReturn(List.of(task));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(17L)).thenReturn(List.of());
        when(deviceSlotService.hasSkuSlots("CAB-GRAVITY", "SKU-COLA")).thenReturn(true);
        when(deviceSlotService.allocateRestockQuantity("CAB-GRAVITY", "SKU-COLA", 3))
                .thenReturn(List.of(new DeviceSlotService.SlotRestockAllocation("A1", 3)));

        ReplenishmentTaskLineDto dto = new ReplenishmentTaskLineDto(
                null, "RESTOCK", "SKU-COLA", "可乐", "B-01", null, null, 3, null, false);

        replenishmentService.submitTaskLines(1L, 17L, new SubmitReplenishmentLinesRequest(List.of(dto)));

        org.mockito.ArgumentCaptor<com.aicabinet.trade.domain.ReplenishmentTaskLine> captor =
                org.mockito.ArgumentCaptor.forClass(com.aicabinet.trade.domain.ReplenishmentTaskLine.class);
        verify(taskLineRepository).save(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("A1", captor.getValue().getSlotId());
        org.junit.jupiter.api.Assertions.assertEquals(3, captor.getValue().getQuantity());
    }
}
