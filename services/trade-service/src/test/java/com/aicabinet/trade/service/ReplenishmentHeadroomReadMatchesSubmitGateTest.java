package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ReplenishmentHeadroomDto;
import com.aicabinet.common.dto.ReplenishmentTaskLineDto;
import com.aicabinet.common.dto.SubmitReplenishmentLinesRequest;
import com.aicabinet.trade.domain.DeviceSkuInventory;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D28-A5（CB-075）：商户端看到的「可补量」与提交时真正拦人的那个数必须是同一个。
 * 此前小程序只算「上限 − 在架」，少扣「他单已领未上柜」⇒ 加得进去、点提交才 400。
 * 本类钉三件事：①下发口径（capacity − 在架 − 他单在途）；②边界（读到 N 就能提交 N，N+1 必被拦）；
 * ③报错文案里的四个数与下发字段逐字相等（否则前端提示与后端拒绝各说一套）。
 */
@ExtendWith(MockitoExtension.class)
class ReplenishmentHeadroomReadMatchesSubmitGateTest {

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
    @Mock private DeviceSkuInventoryMapper inventoryRepository;

    private ReplenishmentService replenishmentService;

    @BeforeEach
    void setUp() {
        replenishmentService = new ReplenishmentService(
                inventoryRepository, routeRepository, taskRepository, taskLineRepository, null, null, null,
                pullOffTaskRepository, new ObjectMapper(), warehouseService, deviceRepository, deviceSlotService,
                inTransitService, sessionService, notificationService, distributedLockService, systemConfigService,
                null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(replenishmentService, "self", replenishmentService);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
    }

    private static ReplenishmentTask doorTask(long taskId, String deviceId, Long outboundId) {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setTaskId(taskId);
        task.setDeviceId(deviceId);
        task.setRouteId(1L);
        task.setStatus("IN_PROGRESS");
        task.setOutboundId(outboundId);
        return task;
    }

    private static DeviceSkuInventory inv(String deviceId, String skuId, int quantity, int capacity) {
        DeviceSkuInventory row = new DeviceSkuInventory();
        row.setDeviceId(deviceId);
        row.setSkuId(skuId);
        row.setQuantity(quantity);
        row.setCapacity(capacity);
        return row;
    }

    private static ReplenishmentTaskLineDto restock(String skuId, int quantity) {
        return new ReplenishmentTaskLineDto(null, "RESTOCK", skuId, "雪碧", "B-01", null, null, quantity, null, false);
    }

    @Test
    void taskHeadroom_roomSubtractsOtherOutboundInTransitNotOwnOutbound() {
        // 台账 20、在架 5、他单在途 10、本单在途 8：可补 5（本单在途不扣——铁律 80，那就是这趟要上的货）
        ReplenishmentTask task = doorTask(31L, "CAB-HR-1", 900L);
        when(deviceSlotService.isDoorCabinet("CAB-HR-1")).thenReturn(true);
        when(inventoryRepository.findByIdDeviceId("CAB-HR-1"))
                .thenReturn(List.of(inv("CAB-HR-1", "SKU-SPRITE", 5, 20)));
        when(inTransitService.qtyBySkuForDeviceExcludingOutbound("CAB-HR-1", 900L))
                .thenReturn(Map.of("SKU-SPRITE", 10));

        List<ReplenishmentHeadroomDto> headroom = replenishmentService.taskHeadroom(task);

        assertEquals(1, headroom.size());
        ReplenishmentHeadroomDto h = headroom.get(0);
        assertEquals("SKU-SPRITE", h.skuId());
        assertEquals(20, h.capacity());
        assertEquals(5, h.onShelfQty());
        assertEquals(10, h.otherInTransitQty());
        assertEquals(5, h.room());
        // 排除的是「本单出库」而不是「全部在途」：必须把 outboundId 传给在途查询
        verify(inTransitService).qtyBySkuForDeviceExcludingOutbound("CAB-HR-1", 900L);
    }

    @Test
    void taskHeadroom_roomNeverNegative_WhenLedgerAlreadyOverFilled() {
        // 在架 18 + 他单在途 5 > 上限 20 ⇒ room=0（只读兜底：下发负数会让前端钳制逻辑疯掉）
        ReplenishmentTask task = doorTask(32L, "CAB-HR-2", null);
        when(deviceSlotService.isDoorCabinet("CAB-HR-2")).thenReturn(true);
        when(inventoryRepository.findByIdDeviceId("CAB-HR-2"))
                .thenReturn(List.of(inv("CAB-HR-2", "SKU-SPRITE", 18, 20)));
        when(inTransitService.qtyBySkuForDeviceExcludingOutbound("CAB-HR-2", null))
                .thenReturn(Map.of("SKU-SPRITE", 5));

        assertEquals(0, replenishmentService.taskHeadroom(task).get(0).room());
    }

    @Test
    void taskHeadroom_capacityUnset_isOmittedMeaningUnlimited() {
        // capacity≤0 = 未配置容量 ⇒ 不进结果（依据缺失时 fail-open，录入容量后自动生效）
        ReplenishmentTask task = doorTask(33L, "CAB-HR-3", null);
        when(deviceSlotService.isDoorCabinet("CAB-HR-3")).thenReturn(true);
        when(inventoryRepository.findByIdDeviceId("CAB-HR-3"))
                .thenReturn(List.of(inv("CAB-HR-3", "SKU-NOCAP", 1, 0), inv("CAB-HR-3", "SKU-COLA", 2, 4)));
        when(inTransitService.qtyBySkuForDeviceExcludingOutbound("CAB-HR-3", null))
                .thenReturn(Map.of());

        List<ReplenishmentHeadroomDto> headroom = replenishmentService.taskHeadroom(task);

        assertEquals(1, headroom.size());
        assertEquals("SKU-COLA", headroom.get(0).skuId());
        assertEquals(2, headroom.get(0).room());
    }

    @Test
    void taskHeadroom_slotCabinet_returnsEmpty() {
        // 货道柜的上限来自 device_slot，下发台账水位会让两页数字当面打架
        ReplenishmentTask task = doorTask(34L, "CAB-GRAVITY-1", null);
        when(deviceSlotService.isDoorCabinet("CAB-GRAVITY-1")).thenReturn(false);

        assertTrue(replenishmentService.taskHeadroom(task).isEmpty());
        verify(inventoryRepository, never()).findByIdDeviceId(anyString());
    }

    @Test
    void submittedQuantityEqualToReadRoom_isAccepted() {
        // 读到的 N 必须提交得进——否则下发值本身被自家门禁拒掉（下发比后端还严 = 少补货）
        ReplenishmentTask task = doorTask(35L, "CAB-HR-4", 901L);
        when(taskRepository.findByIdForUpdate(35L)).thenReturn(Optional.of(task));
        when(taskRepository.findByRouteId(1L)).thenReturn(List.of(task));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(35L)).thenReturn(List.of());
        when(deviceSlotService.isDoorCabinet("CAB-HR-4")).thenReturn(true);
        when(inventoryRepository.findByIdDeviceId("CAB-HR-4"))
                .thenReturn(List.of(inv("CAB-HR-4", "SKU-SPRITE", 5, 20)));
        when(inTransitService.qtyBySkuForDeviceExcludingOutbound("CAB-HR-4", 901L))
                .thenReturn(Map.of("SKU-SPRITE", 10));

        int room = replenishmentService.taskHeadroom(task).get(0).room();
        assertEquals(5, room);
        replenishmentService.submitTaskLines(1L, 35L, new SubmitReplenishmentLinesRequest(List.of(restock("SKU-SPRITE", room))));

        verify(taskLineRepository).save(any(ReplenishmentTaskLine.class));
    }

    @Test
    void submittedQuantityOneOverReadRoom_isRejectedWithSameNumbers() {
        // 读到 5 却提交 6 ⇒ 必须拒；且报错文案里的四个数与下发字段逐字相等
        ReplenishmentTask task = doorTask(36L, "CAB-HR-5", 902L);
        when(taskRepository.findByIdForUpdate(36L)).thenReturn(Optional.of(task));
        when(deviceSlotService.isDoorCabinet("CAB-HR-5")).thenReturn(true);
        when(inventoryRepository.findByIdDeviceId("CAB-HR-5"))
                .thenReturn(List.of(inv("CAB-HR-5", "SKU-SPRITE", 5, 20)));
        when(inTransitService.qtyBySkuForDeviceExcludingOutbound("CAB-HR-5", 902L))
                .thenReturn(Map.of("SKU-SPRITE", 10));

        ReplenishmentHeadroomDto h = replenishmentService.taskHeadroom(task).get(0);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> replenishmentService
                .submitTaskLines(1L, 36L, new SubmitReplenishmentLinesRequest(List.of(restock("SKU-SPRITE", h.room() + 1)))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("商品 SKU-SPRITE 超出台账可补容量：上限 20，在架 5，他单已领未上柜 10，最多再补 5",
                ex.getReason());
        verify(taskLineRepository, never()).save(any(ReplenishmentTaskLine.class));
        verify(taskLineRepository, never()).deleteByTaskIdAndAppliedFalse(anyLong());
    }

    @Test
    void multiLineSameSku_sumsAgainstReadRoom() {
        // 合计口径一致：单行都 ≤ 读到的 room，合计超仍拒（否则前端逐行钳制能绕过封顶）
        ReplenishmentTask task = doorTask(37L, "CAB-HR-6", null);
        when(taskRepository.findByIdForUpdate(37L)).thenReturn(Optional.of(task));
        when(deviceSlotService.isDoorCabinet("CAB-HR-6")).thenReturn(true);
        when(inventoryRepository.findByIdDeviceId("CAB-HR-6"))
                .thenReturn(List.of(inv("CAB-HR-6", "SKU-SPRITE", 2, 4)));
        when(inTransitService.qtyBySkuForDeviceExcludingOutbound("CAB-HR-6", null))
                .thenReturn(Map.of());

        int room = replenishmentService.taskHeadroom(task).get(0).room();
        assertEquals(2, room);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> replenishmentService
                .submitTaskLines(1L, 37L,
                        new SubmitReplenishmentLinesRequest(List.of(restock("SKU-SPRITE", room), restock("SKU-SPRITE", 1)))));

        assertEquals("商品 SKU-SPRITE 超出台账可补容量：上限 4，在架 2，他单已领未上柜 0，最多再补 2", ex.getReason());
    }

    @Test
    void capacityUnset_isNotCappedOnSubmitEither() {
        // 下发里没有 = 不设限；提交侧必须同判据（只在下发拦、提交不拦 = 一半的口径）
        ReplenishmentTask task = doorTask(38L, "CAB-HR-7", null);
        when(taskRepository.findByIdForUpdate(38L)).thenReturn(Optional.of(task));
        when(taskRepository.findByRouteId(1L)).thenReturn(List.of(task));
        when(taskLineRepository.findByTaskIdOrderByLineIdAsc(38L)).thenReturn(List.of());
        when(deviceSlotService.isDoorCabinet("CAB-HR-7")).thenReturn(true);
        when(inventoryRepository.findByIdDeviceId("CAB-HR-7"))
                .thenReturn(List.of(inv("CAB-HR-7", "SKU-NOCAP", 1, 0)));
        when(inTransitService.qtyBySkuForDeviceExcludingOutbound("CAB-HR-7", null))
                .thenReturn(Map.of());

        assertTrue(replenishmentService.taskHeadroom(task).isEmpty());
        replenishmentService.submitTaskLines(1L, 38L, new SubmitReplenishmentLinesRequest(List.of(restock("SKU-NOCAP", 99))));

        verify(taskLineRepository).save(any(ReplenishmentTaskLine.class));
    }
}
