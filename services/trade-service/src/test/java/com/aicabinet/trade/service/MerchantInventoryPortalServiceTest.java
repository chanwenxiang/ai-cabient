package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceInventoryDto;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.mapper.ReplenishmentRouteMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskLineMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantInventoryPortalServiceTest {

    @Mock PermissionService permissionService;
    @Mock MerchantPortalGuard merchantPortalGuard;
    @Mock MerchantFeaturePackService merchantFeaturePackService;
    @Mock DeviceSkuInventoryMapper inventoryRepository;
    @Mock InventoryLotService inventoryLotService;
    @Mock PullOffTaskMapper pullOffTaskRepository;
    @Mock DeviceSlotService deviceSlotService;
    @Mock ReplenishmentTaskMapper replenishmentTaskRepository;
    @Mock ReplenishmentTaskLineMapper replenishmentTaskLineRepository;
    @Mock ReplenishmentRouteMapper replenishmentRouteRepository;
    @Mock DeviceInfoMapper deviceRepository;
    @Mock FileAttachmentService fileAttachmentService;
    @Mock com.aicabinet.trade.mapper.WarehouseOutboundMapper outboundRepository;
    @Mock com.aicabinet.trade.mapper.WarehouseOutboundLineMapper outboundLineRepository;
    @Mock com.aicabinet.trade.mapper.WarehouseInventoryMapper warehouseInventoryRepository;
    @Mock com.aicabinet.trade.mapper.SkuCatalogMapper skuCatalogMapper;

    private MerchantInventoryPortalService service;

    @BeforeEach
    void setUp() {
        service = new MerchantInventoryPortalService(
                permissionService, merchantPortalGuard, merchantFeaturePackService,
                inventoryRepository, inventoryLotService, pullOffTaskRepository, deviceSlotService,
                replenishmentTaskRepository, replenishmentTaskLineRepository, replenishmentRouteRepository,
                deviceRepository, fileAttachmentService, skuCatalogMapper,
                outboundRepository, outboundLineRepository, warehouseInventoryRepository);
    }

    @Test
    void listInventory_whenFieldPackEmpty_returnsEmptyWithoutQuery() {
        when(merchantFeaturePackService.allowedDeviceIdsForPack(9L, MerchantFeaturePacks.FIELD))
                .thenReturn(Set.of());

        List<DeviceInventoryDto> rows = service.listInventory(9L, null, false);

        assertTrue(rows.isEmpty());
        verify(inventoryRepository, never()).findAllLimit(2000);
        verify(inventoryRepository, never()).findLowStockLimit(500);
    }

    @Test
    void getReplenishmentTaskLines_whenMissing_notFound() {
        when(replenishmentTaskRepository.findById(404L)).thenReturn(java.util.Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getReplenishmentTaskLines(1L, 404L));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void haversineCheckInDistanceM_samePointIsNearZero() {
        DeviceInfo device = new DeviceInfo();
        device.setLatitude(31.2304);
        device.setLongitude(121.4737);

        Double meters = MerchantInventoryPortalService.haversineCheckInDistanceM(
                device, 31.2304, 121.4737);

        assertTrue(meters != null && meters < 1.0);
    }

    @Test
    void haversineCheckInDistanceM_missingCoords_returnsNull() {
        assertNull(MerchantInventoryPortalService.haversineCheckInDistanceM(null, 1.0, 2.0));
        DeviceInfo device = new DeviceInfo();
        assertNull(MerchantInventoryPortalService.haversineCheckInDistanceM(device, 1.0, 2.0));
    }

    @Test
    void listShortageSummary_doorCabinet_excludedFromSlotShortage() {
        // 开门柜无货道（判据=将邑绑定）：货道级缺货口径不成立，残留货道行不参与汇总
        when(merchantFeaturePackService.allowedDeviceIdsForPack(9L, MerchantFeaturePacks.FIELD))
                .thenReturn(Set.of("EG-DOOR", "CAB-GRAVITY"));
        DeviceInfo door = new DeviceInfo();
        door.setDeviceId("EG-DOOR");
        DeviceInfo gravity = new DeviceInfo();
        gravity.setDeviceId("CAB-GRAVITY");
        when(deviceRepository.findAll()).thenReturn(List.of(door, gravity));
        when(deviceSlotService.isDoorCabinet("EG-DOOR")).thenReturn(true);
        when(deviceSlotService.listShortageSlots("CAB-GRAVITY")).thenReturn(List.of());

        List<com.aicabinet.common.dto.MerchantShortageSummaryDto> rows = service.listShortageSummary(9L);

        assertTrue(rows.isEmpty());
        verify(deviceSlotService, never()).listShortageSlots("EG-DOOR");
    }

    @Test
    void prepList_groupsByDevice_andConvertsByPackQty() {
        // CB-072 站点自仓口径：IN_PROGRESS 任务出库行按柜机归组、组内按 SKU 聚合，只排除作废行；
        // pack_qty=24 换算 26 支 → 1 件 + 2 支；带该柜分仓库存 30 支（1 件 + 6 支）
        when(merchantFeaturePackService.allowedDeviceIdsForPack(9L, MerchantFeaturePacks.FIELD))
                .thenReturn(null);
        com.aicabinet.trade.domain.ReplenishmentTask task = new com.aicabinet.trade.domain.ReplenishmentTask();
        task.setTaskId(7L);
        task.setDeviceId("EG-1");
        task.setOutboundId(70L);
        task.setStatus("IN_PROGRESS");
        when(replenishmentTaskRepository.findByStatusIn(java.util.List.of("PENDING", "IN_PROGRESS")))
                .thenReturn(java.util.List.of(task));
        com.aicabinet.trade.domain.WarehouseOutbound outbound = new com.aicabinet.trade.domain.WarehouseOutbound();
        outbound.setOutboundId(70L);
        outbound.setWarehouseId("WH-1");
        when(outboundRepository.selectById(70L)).thenReturn(outbound);
        com.aicabinet.trade.domain.WarehouseOutboundLine pick = new com.aicabinet.trade.domain.WarehouseOutboundLine();
        pick.setSkuId("100013");
        pick.setQuantity(26);
        pick.setHandoverStatus("PENDING");
        pick.setPicked(false);
        com.aicabinet.trade.domain.WarehouseOutboundLine cancelled = new com.aicabinet.trade.domain.WarehouseOutboundLine();
        cancelled.setSkuId("100014");
        cancelled.setQuantity(4);
        cancelled.setHandoverStatus("CANCELLED");
        cancelled.setPicked(false);
        when(outboundLineRepository.findByOutboundIdAndDeviceIdOrderByLineIdAsc(70L, "EG-1"))
                .thenReturn(java.util.List.of(pick, cancelled));
        com.aicabinet.trade.domain.SkuCatalog catalog = new com.aicabinet.trade.domain.SkuCatalog();
        catalog.setSkuId("100013");
        catalog.setSkuName("雪碧柠檬味汽水330ml");
        catalog.setUnit("罐");
        catalog.setPackQty(24);
        when(skuCatalogMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(catalog));
        com.aicabinet.trade.domain.DeviceInfo device = new com.aicabinet.trade.domain.DeviceInfo();
        device.setDeviceId("EG-1");
        device.setDeviceName("将邑测试机");
        when(deviceRepository.selectBatchIds(java.util.Set.of("EG-1")))
                .thenReturn(java.util.List.of(device));
        com.aicabinet.trade.domain.WarehouseInventory stock = new com.aicabinet.trade.domain.WarehouseInventory();
        stock.setWarehouseId("WH-1");
        stock.setSkuId("100013");
        stock.setQuantity(30);
        when(warehouseInventoryRepository.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(stock));

        java.util.List<com.aicabinet.common.dto.MerchantPrepGroupDto> out =
                service.replenishmentPrepList(9L);

        assertEquals(1, out.size());
        com.aicabinet.common.dto.MerchantPrepGroupDto group = out.get(0);
        assertEquals("EG-1", group.deviceId());
        assertEquals("将邑测试机", group.deviceName());
        assertEquals(1, group.taskCount());
        assertEquals(1, group.items().size());
        com.aicabinet.common.dto.MerchantPrepItemDto row = group.items().get(0);
        assertEquals("100013", row.skuId());
        assertEquals(26, row.totalUnits());
        assertEquals(1, row.cases());
        assertEquals(2, row.remainUnits());
        assertEquals(30, row.stockUnits());
        assertFalse(row.stockShort());
    }

    @Test
    void prepList_sameSkuOnTwoDevices_staysInOwnGroup_withOwnWarehouseStock() {
        // CB-073：同 SKU 两台柜不合并——各柜数量、任务数与分仓库存独立
        when(merchantFeaturePackService.allowedDeviceIdsForPack(9L, MerchantFeaturePacks.FIELD))
                .thenReturn(null);
        com.aicabinet.trade.domain.ReplenishmentTask t1 = new com.aicabinet.trade.domain.ReplenishmentTask();
        t1.setTaskId(71L);
        t1.setDeviceId("EG-1");
        t1.setOutboundId(700L);
        t1.setStatus("IN_PROGRESS");
        com.aicabinet.trade.domain.ReplenishmentTask t2 = new com.aicabinet.trade.domain.ReplenishmentTask();
        t2.setTaskId(72L);
        t2.setDeviceId("EG-2");
        t2.setOutboundId(701L);
        t2.setStatus("PENDING");
        when(replenishmentTaskRepository.findByStatusIn(java.util.List.of("PENDING", "IN_PROGRESS")))
                .thenReturn(java.util.List.of(t1, t2));
        com.aicabinet.trade.domain.WarehouseOutbound whA = new com.aicabinet.trade.domain.WarehouseOutbound();
        whA.setOutboundId(700L);
        whA.setWarehouseId("WH-A");
        com.aicabinet.trade.domain.WarehouseOutbound whB = new com.aicabinet.trade.domain.WarehouseOutbound();
        whB.setOutboundId(701L);
        whB.setWarehouseId("WH-B");
        when(outboundRepository.selectById(700L)).thenReturn(whA);
        when(outboundRepository.selectById(701L)).thenReturn(whB);
        com.aicabinet.trade.domain.WarehouseOutboundLine lineA =
                new com.aicabinet.trade.domain.WarehouseOutboundLine();
        lineA.setSkuId("100013");
        lineA.setQuantity(2);
        lineA.setHandoverStatus("PENDING");
        lineA.setPicked(false);
        com.aicabinet.trade.domain.WarehouseOutboundLine lineB =
                new com.aicabinet.trade.domain.WarehouseOutboundLine();
        lineB.setSkuId("100013");
        lineB.setQuantity(24);
        lineB.setHandoverStatus("PENDING");
        lineB.setPicked(false);
        when(outboundLineRepository.findByOutboundIdAndDeviceIdOrderByLineIdAsc(700L, "EG-1"))
                .thenReturn(java.util.List.of(lineA));
        when(outboundLineRepository.findByOutboundIdAndDeviceIdOrderByLineIdAsc(701L, "EG-2"))
                .thenReturn(java.util.List.of(lineB));
        com.aicabinet.trade.domain.DeviceInfo d1 = new com.aicabinet.trade.domain.DeviceInfo();
        d1.setDeviceId("EG-1");
        d1.setDeviceName("一号柜");
        com.aicabinet.trade.domain.DeviceInfo d2 = new com.aicabinet.trade.domain.DeviceInfo();
        d2.setDeviceId("EG-2");
        d2.setDeviceName("二号柜");
        when(deviceRepository.selectBatchIds(java.util.Set.of("EG-1", "EG-2")))
                .thenReturn(java.util.List.of(d1, d2));
        when(skuCatalogMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
        com.aicabinet.trade.domain.WarehouseInventory stockA =
                new com.aicabinet.trade.domain.WarehouseInventory();
        stockA.setWarehouseId("WH-A");
        stockA.setSkuId("100013");
        stockA.setQuantity(5);
        when(warehouseInventoryRepository.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(stockA));

        java.util.List<com.aicabinet.common.dto.MerchantPrepGroupDto> out =
                service.replenishmentPrepList(9L);

        assertEquals(2, out.size());
        // 柜机按 deviceId 升序
        assertEquals("EG-1", out.get(0).deviceId());
        assertEquals("EG-2", out.get(1).deviceId());
        assertEquals(2, out.get(0).items().get(0).totalUnits());
        assertEquals(5, out.get(0).items().get(0).stockUnits());
        assertEquals(24, out.get(1).items().get(0).totalUnits());
        // EG-2 的出库挂 WH-B，那里没有库存 → 不足
        assertTrue(out.get(1).items().get(0).stockShort());
        assertEquals(1, out.get(0).taskCount());
        assertEquals(1, out.get(1).taskCount());
    }
}
