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
    void listInventory_lowStockOnly_excludesDelistedRows() {
        when(merchantFeaturePackService.allowedDeviceIdsForPack(9L, MerchantFeaturePacks.FIELD))
                .thenReturn(Set.of("EG-1"));
        when(inventoryRepository.findByIdDeviceId("EG-1"))
                .thenReturn(List.of(invRow("EG-1", "100013"), invRow("EG-1", "100016")));
        when(inventoryLotService.lotLedgerSkuIds("EG-1")).thenReturn(Set.of());
        when(skuCatalogMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(catalog("100013", "ACTIVE"), catalog("100016", "INACTIVE")));

        List<DeviceInventoryDto> rows = service.listInventory(9L, "EG-1", true);

        assertEquals(1, rows.size());
        assertEquals("100013", rows.get(0).skuId());
    }

    @Test
    void listShortageSummary_doorCabinet_skipsDelistedSkus() {
        // 下架 SKU 不进缺货面（2026-10-30 拍板）：qty=0 的下架行不得算进「待补商品数/缺货件数」
        when(merchantFeaturePackService.allowedDeviceIdsForPack(9L, MerchantFeaturePacks.FIELD))
                .thenReturn(Set.of("EG-DOOR"));
        DeviceInfo door = new DeviceInfo();
        door.setDeviceId("EG-DOOR");
        when(deviceRepository.findAll()).thenReturn(List.of(door));
        when(deviceSlotService.isDoorCabinet("EG-DOOR")).thenReturn(true);
        when(inventoryRepository.findByIdDeviceId("EG-DOOR"))
                .thenReturn(List.of(invRow("EG-DOOR", "100013"), invRow("EG-DOOR", "100016")));
        when(inventoryLotService.lotLedgerSkuIds("EG-DOOR")).thenReturn(Set.of());
        when(skuCatalogMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(catalog("100013", "ACTIVE"), catalog("100016", "INACTIVE")));

        List<com.aicabinet.common.dto.MerchantShortageSummaryDto> rows = service.listShortageSummary(9L);

        assertEquals(1, rows.size());
        assertEquals("EG-DOOR", rows.get(0).deviceId());
        assertEquals(1, rows.get(0).shortageSlots());
        assertEquals(4, rows.get(0).shortageQty());
    }

    private static com.aicabinet.trade.domain.DeviceSkuInventory invRow(String dev, String sku) {
        com.aicabinet.trade.domain.DeviceSkuInventory inv = new com.aicabinet.trade.domain.DeviceSkuInventory();
        inv.setId(new com.aicabinet.trade.domain.DeviceSkuInventoryId(dev, sku));
        inv.setQuantity(0);
        inv.setCapacity(4);
        inv.setLowThreshold(2);
        return inv;
    }

    private static com.aicabinet.trade.domain.SkuCatalog catalog(String skuId, String status) {
        com.aicabinet.trade.domain.SkuCatalog sku = new com.aicabinet.trade.domain.SkuCatalog();
        sku.setSkuId(skuId);
        sku.setStatus(status);
        return sku;
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
        task.setCreatedAt(java.time.Instant.parse("2026-10-30T03:20:00Z"));
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
        // 备货单「已生成」按最近任务创建时间排序，时间必须由服务端给到前端
        assertEquals(java.time.Instant.parse("2026-10-30T03:20:00Z"), group.lastTaskAt());
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
    void prepList_twoTasksSameDevice_lastTaskAtIsNewestNotIterationOrder() {
        // 同柜多张进行中任务：时间取最大创建时间，与任务返回顺序无关（前端按它正/倒序排）
        when(merchantFeaturePackService.allowedDeviceIdsForPack(9L, MerchantFeaturePacks.FIELD))
                .thenReturn(null);
        com.aicabinet.trade.domain.ReplenishmentTask newer =
                new com.aicabinet.trade.domain.ReplenishmentTask();
        newer.setTaskId(81L);
        newer.setDeviceId("EG-1");
        newer.setOutboundId(810L);
        newer.setStatus("IN_PROGRESS");
        newer.setCreatedAt(java.time.Instant.parse("2026-10-30T06:00:00Z"));
        com.aicabinet.trade.domain.ReplenishmentTask older =
                new com.aicabinet.trade.domain.ReplenishmentTask();
        older.setTaskId(82L);
        older.setDeviceId("EG-1");
        older.setOutboundId(820L);
        older.setStatus("PENDING");
        older.setCreatedAt(java.time.Instant.parse("2026-10-29T06:00:00Z"));
        when(replenishmentTaskRepository.findByStatusIn(java.util.List.of("PENDING", "IN_PROGRESS")))
                .thenReturn(java.util.List.of(newer, older));
        com.aicabinet.trade.domain.WarehouseOutbound ob1 =
                new com.aicabinet.trade.domain.WarehouseOutbound();
        ob1.setOutboundId(810L);
        ob1.setWarehouseId("WH-1");
        com.aicabinet.trade.domain.WarehouseOutbound ob2 =
                new com.aicabinet.trade.domain.WarehouseOutbound();
        ob2.setOutboundId(820L);
        ob2.setWarehouseId("WH-1");
        when(outboundRepository.selectById(810L)).thenReturn(ob1);
        when(outboundRepository.selectById(820L)).thenReturn(ob2);
        com.aicabinet.trade.domain.WarehouseOutboundLine line =
                new com.aicabinet.trade.domain.WarehouseOutboundLine();
        line.setSkuId("100013");
        line.setQuantity(2);
        line.setHandoverStatus("PENDING");
        line.setPicked(false);
        when(outboundLineRepository.findByOutboundIdAndDeviceIdOrderByLineIdAsc(810L, "EG-1"))
                .thenReturn(java.util.List.of(line));
        when(outboundLineRepository.findByOutboundIdAndDeviceIdOrderByLineIdAsc(820L, "EG-1"))
                .thenReturn(java.util.List.of(line));
        com.aicabinet.trade.domain.SkuCatalog catalog =
                new com.aicabinet.trade.domain.SkuCatalog();
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
        when(warehouseInventoryRepository.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());

        java.util.List<com.aicabinet.common.dto.MerchantPrepGroupDto> out =
                service.replenishmentPrepList(9L);

        assertEquals(1, out.size());
        assertEquals(2, out.get(0).taskCount());
        assertEquals(java.time.Instant.parse("2026-10-30T06:00:00Z"), out.get(0).lastTaskAt());
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

    @Test
    void prepList_capsUnitsByInventoryCapacity() {
        // 2026-10-30 实测案：同柜同 SKU 两条进行中任务（运营按建议建 + 商户自主发起）各 3 罐，
        // 相加备 6，而商品容量只有 4。备货量必须钳到「容量 − 可售」= 4 − 1 = 3：
        // 柜里放不下的不领（任务条目数仍如实显示 2 条）。
        // 对照：没有台账行的 SKU（容量未知）保持任务线数量，不被误钳为 0。
        when(merchantFeaturePackService.allowedDeviceIdsForPack(9L, MerchantFeaturePacks.FIELD))
                .thenReturn(null);
        com.aicabinet.trade.domain.ReplenishmentTask t1 = new com.aicabinet.trade.domain.ReplenishmentTask();
        t1.setTaskId(81L);
        t1.setDeviceId("EG-1");
        t1.setOutboundId(810L);
        t1.setStatus("IN_PROGRESS");
        com.aicabinet.trade.domain.ReplenishmentTask t2 = new com.aicabinet.trade.domain.ReplenishmentTask();
        t2.setTaskId(82L);
        t2.setDeviceId("EG-1");
        t2.setOutboundId(820L);
        t2.setStatus("IN_PROGRESS");
        when(replenishmentTaskRepository.findByStatusIn(java.util.List.of("PENDING", "IN_PROGRESS")))
                .thenReturn(java.util.List.of(t1, t2));
        com.aicabinet.trade.domain.WarehouseOutbound wh = new com.aicabinet.trade.domain.WarehouseOutbound();
        wh.setWarehouseId("WH-1");
        when(outboundRepository.selectById(810L)).thenReturn(wh);
        when(outboundRepository.selectById(820L)).thenReturn(wh);
        com.aicabinet.trade.domain.WarehouseOutboundLine lineA =
                new com.aicabinet.trade.domain.WarehouseOutboundLine();
        lineA.setSkuId("100014");
        lineA.setQuantity(3);
        lineA.setHandoverStatus("PENDING");
        com.aicabinet.trade.domain.WarehouseOutboundLine lineB =
                new com.aicabinet.trade.domain.WarehouseOutboundLine();
        lineB.setSkuId("100014");
        lineB.setQuantity(3);
        lineB.setHandoverStatus("PENDING");
        com.aicabinet.trade.domain.WarehouseOutboundLine lineC =
                new com.aicabinet.trade.domain.WarehouseOutboundLine();
        lineC.setSkuId("100015");
        lineC.setQuantity(3);
        lineC.setHandoverStatus("PENDING");
        when(outboundLineRepository.findByOutboundIdAndDeviceIdOrderByLineIdAsc(810L, "EG-1"))
                .thenReturn(java.util.List.of(lineA, lineC));
        when(outboundLineRepository.findByOutboundIdAndDeviceIdOrderByLineIdAsc(820L, "EG-1"))
                .thenReturn(java.util.List.of(lineB));
        com.aicabinet.trade.domain.SkuCatalog catalog = new com.aicabinet.trade.domain.SkuCatalog();
        catalog.setSkuId("100014");
        catalog.setSkuName("加多宝凉茶植物饮料");
        catalog.setUnit("罐");
        catalog.setPackQty(24);
        when(skuCatalogMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(catalog));
        com.aicabinet.trade.domain.DeviceInfo device = new com.aicabinet.trade.domain.DeviceInfo();
        device.setDeviceId("EG-1");
        device.setDeviceName("将邑测试机");
        when(deviceRepository.selectBatchIds(java.util.Set.of("EG-1")))
                .thenReturn(java.util.List.of(device));
        when(warehouseInventoryRepository.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
        com.aicabinet.trade.domain.DeviceSkuInventory row = new com.aicabinet.trade.domain.DeviceSkuInventory();
        row.setId(new com.aicabinet.trade.domain.DeviceSkuInventoryId("EG-1", "100014"));
        row.setQuantity(1);
        row.setCapacity(4);
        row.setLowThreshold(1);
        when(inventoryRepository.findByIdDeviceId("EG-1")).thenReturn(java.util.List.of(row));
        when(inventoryLotService.lotLedgerSkuIds("EG-1")).thenReturn(java.util.Set.of());

        java.util.List<com.aicabinet.common.dto.MerchantPrepGroupDto> out =
                service.replenishmentPrepList(9L);

        assertEquals(1, out.size());
        assertEquals(2, out.get(0).taskCount(), "任务条目数如实保留 2 条，别用钳制掩盖重复任务");
        java.util.Map<String, Integer> unitsBySku = out.get(0).items().stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.aicabinet.common.dto.MerchantPrepItemDto::skuId,
                        com.aicabinet.common.dto.MerchantPrepItemDto::totalUnits));
        assertEquals(3, unitsBySku.get("100014"), "重复任务 3+3 必须钳到容量4−可售1=3");
        assertEquals(3, unitsBySku.get("100015"), "没有容量台账行 ⇒ 保持任务线数量，不被误钳");
    }
}
