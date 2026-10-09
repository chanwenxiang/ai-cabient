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
                new ObjectMapper(), warehouseService, null, deviceSlotService, inTransitService,
                sessionService, null, null, notificationService, distributedLockService, null, null, null);
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
