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
    void prepList_aggregatesBySku_andConvertsByPackQty() {
        // CB-072 站点自仓口径：IN_PROGRESS 任务出库行按 SKU 聚合，只排除作废行；
        // pack_qty=24 换算 26 支 → 1 件 + 2 支；带分仓库存 30 支（1 件 + 6 支）
        when(merchantFeaturePackService.allowedDeviceIdsForPack(9L, MerchantFeaturePacks.FIELD))
                .thenReturn(null);
        com.aicabinet.trade.domain.ReplenishmentTask task = new com.aicabinet.trade.domain.ReplenishmentTask();
        task.setTaskId(7L);
        task.setDeviceId("EG-1");
        task.setOutboundId(70L);
        task.setStatus("IN_PROGRESS");
        when(replenishmentTaskRepository.findByStatusIn(java.util.List.of("IN_PROGRESS")))
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
        com.aicabinet.trade.domain.WarehouseInventory stock = new com.aicabinet.trade.domain.WarehouseInventory();
        stock.setWarehouseId("WH-1");
        stock.setSkuId("100013");
        stock.setQuantity(30);
        when(warehouseInventoryRepository.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(stock));

        java.util.List<com.aicabinet.common.dto.MerchantPrepListDto> out =
                service.replenishmentPrepList(9L);

        assertEquals(1, out.size());
        com.aicabinet.common.dto.MerchantPrepListDto row = out.get(0);
        assertEquals("100013", row.skuId());
        assertEquals(26, row.totalUnits());
        assertEquals(1, row.cases());
        assertEquals(2, row.remainUnits());
        assertEquals(30, row.stockUnits());
        assertFalse(row.stockShort());
        assertEquals(1, row.taskCount());
    }
}
