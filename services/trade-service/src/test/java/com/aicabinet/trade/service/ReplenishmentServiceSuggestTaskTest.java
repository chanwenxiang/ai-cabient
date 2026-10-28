package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ReplenishmentSuggestDto;
import com.aicabinet.trade.domain.ReplenishmentRoute;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.ReplenishmentTaskLine;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CB-018 ①：补货建议一键生成补货任务。口径与建议页一致（fillToPar=false），
 * 空 gap 必须 400 拒绝（复用 REPLENISHMENT_NO_GAP），RESTOCK 行复用 seedDraftRestockLines 的
 * 货道分配 + 容量不足兜底行。
 */
@ExtendWith(MockitoExtension.class)
class ReplenishmentServiceSuggestTaskTest {

    private static final Long ACTOR = 100_000_001L;
    private static final String DEVICE = "CAB-001";

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
    private com.aicabinet.trade.mapper.DeviceInfoMapper deviceRepository;
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

    private ReplenishmentService service;

    @BeforeEach
    void setUp() {
        service = new ReplenishmentService(
                null, routeRepository, taskRepository, taskLineRepository, null, null, null, pullOffTaskRepository,
                new ObjectMapper(), warehouseService, deviceRepository, deviceSlotService, inTransitService,
                sessionService, notificationService, distributedLockService, null, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        lenient().when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
    }

    private static ReplenishmentSuggestDto suggest(String skuId, int qty) {
        return new ReplenishmentSuggestDto(DEVICE, skuId, 0, 0, 0, qty, 0, 0, 0, 0, "test");
    }

    @Test
    void createTaskFromSuggestion_createsRouteTaskAndRestockLines() {
        when(warehouseService.suggestForDevice(DEVICE)).thenReturn(List.of(
                suggest("SKU-A", 5),
                suggest("SKU-B", 0)   // suggestQty=0 必须被过滤
        ));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(10L)).thenReturn(List.of());
        when(deviceSlotService.allocateRestockQuantity(DEVICE, "SKU-A", 5))
                .thenReturn(List.of(new DeviceSlotService.SlotRestockAllocation("A1", 3),
                        new DeviceSlotService.SlotRestockAllocation("A2", 2)));
        when(routeRepository.save(any())).thenAnswer(inv -> {
            ReplenishmentRoute r = inv.getArgument(0);
            r.setRouteId(7L);
            return r;
        });
        when(taskRepository.save(any())).thenAnswer(inv -> {
            ReplenishmentTask t = inv.getArgument(0);
            t.setTaskId(10L);
            return t;
        });
        when(taskRepository.findByRouteId(7L)).thenReturn(List.of());

        service.createTaskFromSuggestion(ACTOR, " " + DEVICE + " ", null);

        ArgumentCaptor<ReplenishmentRoute> routeCaptor = ArgumentCaptor.forClass(ReplenishmentRoute.class);
        verify(routeRepository).save(routeCaptor.capture());
        assertEquals("PLANNED", routeCaptor.getValue().getStatus());
        // 未显式指派 → 默认操作人（与临期先例 createTaskFromPullOff 一致）
        assertEquals(ACTOR, routeCaptor.getValue().getAssigneeUserId());

        ArgumentCaptor<ReplenishmentTask> taskCaptor = ArgumentCaptor.forClass(ReplenishmentTask.class);
        verify(taskRepository).save(taskCaptor.capture());
        ReplenishmentTask task = taskCaptor.getValue();
        assertEquals("PENDING", task.getStatus());
        assertEquals(DEVICE, task.getDeviceId());
        assertEquals("from-suggest:" + DEVICE, task.getNotes());

        ArgumentCaptor<ReplenishmentTaskLine> lineCaptor = ArgumentCaptor.forClass(ReplenishmentTaskLine.class);
        verify(taskLineRepository, org.mockito.Mockito.times(2)).save(lineCaptor.capture());
        List<ReplenishmentTaskLine> lines = lineCaptor.getAllValues();
        assertEquals("RESTOCK", lines.get(0).getLineType());
        assertEquals("SKU-A", lines.get(0).getSkuId());
        assertEquals("A1", lines.get(0).getSlotId());
        assertEquals(3, lines.get(0).getQuantity());
        assertEquals("A2", lines.get(1).getSlotId());
        assertEquals(2, lines.get(1).getQuantity());
    }

    @Test
    void createTaskFromSuggestion_withHomeWarehouse_createsOutboundInsteadOfDraftLines() {
        // P0-1（2026-10-15）：设备有主仓 ⇒ 同事务生成出库单，不再种无批次草稿行。
        // 2026-10-28 补货=自提自送口径：建出库单后**立即**种现场补货明细（generateLinesFromOutbound），
        // 不再等「发运」回填——补货场景无发运环节，否则任务明细恒空、核对流程走不通。
        when(warehouseService.suggestForDevice(DEVICE)).thenReturn(List.of(suggest("SKU-A", 5)));
        com.aicabinet.trade.domain.DeviceInfo device = new com.aicabinet.trade.domain.DeviceInfo();
        device.setDeviceId(DEVICE);
        device.setHomeWarehouseId("WH-1");
        when(deviceRepository.findById(DEVICE)).thenReturn(java.util.Optional.of(device));
        when(routeRepository.save(any())).thenAnswer(inv -> {
            ReplenishmentRoute r = inv.getArgument(0);
            r.setRouteId(7L);
            return r;
        });
        when(taskRepository.save(any())).thenAnswer(inv -> {
            ReplenishmentTask t = inv.getArgument(0);
            t.setTaskId(10L);
            return t;
        });
        com.aicabinet.common.dto.WarehouseOutboundDto outbound =
                new com.aicabinet.common.dto.WarehouseOutboundDto(
                        88L, null, 7L, null, null, null, null, null, null);
        when(warehouseService.createOutboundForRoute(7L, null, ACTOR)).thenReturn(outbound);
        // generateLinesFromOutbound(88L) 会按 outboundId 查任务；此路径下无任务（任务行由出库行驱动，
        // 而 createOutboundForRoute 已把 outboundId 回写到任务上——单测聚焦「不再种草稿行」语义）
        when(taskRepository.findByOutboundId(88L)).thenReturn(List.of());

        service.createTaskFromSuggestion(ACTOR, DEVICE, null);

        verify(warehouseService).createOutboundForRoute(7L, null, ACTOR);
        // 有主仓：不种草稿行（seedDraftRestockLines 不走），但会立即 generateLinesFromOutbound 回填明细
        verify(taskLineRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void createTaskFromSuggestion_explicitAssignee_wins() {
        Long assignee = 100_000_009L;
        when(warehouseService.suggestForDevice(DEVICE)).thenReturn(List.of(suggest("SKU-A", 5)));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(10L)).thenReturn(List.of());
        when(deviceSlotService.allocateRestockQuantity(DEVICE, "SKU-A", 5))
                .thenReturn(List.of(new DeviceSlotService.SlotRestockAllocation("A1", 5)));
        when(routeRepository.save(any())).thenAnswer(inv -> {
            ReplenishmentRoute r = inv.getArgument(0);
            r.setRouteId(7L);
            return r;
        });
        when(taskRepository.save(any())).thenAnswer(inv -> {
            ReplenishmentTask t = inv.getArgument(0);
            t.setTaskId(10L);
            return t;
        });
        when(taskRepository.findByRouteId(7L)).thenReturn(List.of());

        service.createTaskFromSuggestion(ACTOR, DEVICE, assignee);

        ArgumentCaptor<ReplenishmentRoute> routeCaptor = ArgumentCaptor.forClass(ReplenishmentRoute.class);
        verify(routeRepository).save(routeCaptor.capture());
        assertEquals(assignee, routeCaptor.getValue().getAssigneeUserId());
    }

    @Test
    void createTaskFromSuggestion_noGap_rejects400() {
        when(warehouseService.suggestForDevice(DEVICE)).thenReturn(List.of(suggest("SKU-A", 0)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createTaskFromSuggestion(ACTOR, DEVICE, null));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void createTaskFromSuggestion_blankDevice_rejects400() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createTaskFromSuggestion(ACTOR, "  ", null));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }
}
