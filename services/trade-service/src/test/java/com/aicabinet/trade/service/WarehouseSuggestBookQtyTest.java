package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ReplenishmentSuggestDto;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.ReplenishmentRouteMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.WarehouseInboundLineMapper;
import com.aicabinet.trade.mapper.WarehouseInboundMapper;
import com.aicabinet.trade.mapper.WarehouseMovementMapper;
import com.aicabinet.trade.mapper.WarehouseOutboundLineMapper;
import com.aicabinet.trade.mapper.WarehouseOutboundMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 补货建议的账面口径（2026-10-27 EG943322621391 用户实测「已有 2 个、最多放 4 个，建议不该是 4」）：
 * 🔴 已建批次账的 SKU 才以账本可售为准；未建账的 SKU 必须回落 device_sku_inventory 账面——
 * 设备级一刀切会把「从未进过账本」的 SKU 读成 0（雪碧账面 2 → 建议 4−0=4 顶穿容量）。
 * 另锁 ROP 建议的容量钳制：销量外推不得顶穿 台账容量 − 账面。
 */
@ExtendWith(MockitoExtension.class)
class WarehouseSuggestBookQtyTest {

    @Mock private WarehouseMapper warehouseRepository;
    @Mock private com.aicabinet.trade.mapper.WarehouseInventoryMapper inventoryRepository;
    @Mock private WarehouseInboundMapper inboundRepository;
    @Mock private WarehouseInboundLineMapper inboundLineRepository;
    @Mock private WarehouseOutboundMapper outboundRepository;
    @Mock private WarehouseOutboundLineMapper outboundLineRepository;
    @Mock private WarehouseMovementMapper movementRepository;
    @Mock private DeviceSkuInventoryMapper deviceInventoryRepository;
    @Mock private ReplenishmentTaskMapper taskRepository;
    @Mock private ReplenishmentRouteMapper routeRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private DeviceSlotService deviceSlotService;
    @Mock private SalesVelocityService salesVelocityService;
    @Mock private InTransitService inTransitService;
    @Mock private InventoryLotService inventoryLotService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private DisplaySnapshotHelper displaySnapshotHelper;
    @Mock private DeviceInfoMapper deviceInfoRepository;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private com.aicabinet.trade.service.PermissionService permissionService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private AdminAuditService auditService;

    private WarehouseService service;

    @BeforeEach
    void setUp() {
        service = new WarehouseService(warehouseRepository, inventoryRepository,
                inboundRepository, inboundLineRepository, outboundRepository, outboundLineRepository,
                movementRepository, deviceInventoryRepository, taskRepository, routeRepository, skuCatalogRepository,
                deviceSlotService, salesVelocityService, inTransitService, inventoryLotService, distributedLockService,
                displaySnapshotHelper, deviceInfoRepository, null,
                userInfoRepository, permissionService, merchantScopeService, auditService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    private static DeviceSkuInventory inv(String skuId, int quantity, int capacity, int lowThreshold) {
        DeviceSkuInventory row = new DeviceSkuInventory();
        row.setId(new com.aicabinet.trade.domain.DeviceSkuInventoryId("EG-1", skuId));
        row.setQuantity(quantity);
        row.setCapacity(capacity);
        row.setLowThreshold(lowThreshold);
        return row;
    }

    private void commonStubs(Map<String, SalesVelocityService.SkuVelocity> velocity) {
        when(inTransitService.qtyBySkuForDevice("EG-1")).thenReturn(Map.of());
        when(salesVelocityService.velocityBySku("EG-1")).thenReturn(velocity);
        when(deviceSlotService.hasSkuSlots(eq("EG-1"), anyString())).thenReturn(false);
    }

    @Test
    void lowStock_skuWithoutLotLedger_usesInventoryQty_notZero() {
        // 用户实测案：雪碧账面 2、账本零行（从未进过账本）→ 建议 = 4−2 = 2，而不是 4−0 = 4
        commonStubs(Map.of());
        when(deviceInventoryRepository.findByIdDeviceId("EG-1"))
                .thenReturn(List.of(inv("100013", 2, 4, 3)));
        when(inventoryLotService.lotLedgerSkuIds("EG-1")).thenReturn(Set.of());

        List<ReplenishmentSuggestDto> out = service.suggestForDevice("EG-1");

        assertEquals(1, out.size());
        ReplenishmentSuggestDto s = out.get(0);
        assertEquals("100013", s.skuId());
        assertEquals(2, s.currentQty());
        assertEquals(2, s.suggestQty());
        assertEquals("LOW_STOCK", s.suggestReason());
    }

    @Test
    void lowStock_skuWithLotLedger_usesLedgerSellable() {
        // 已建账 SKU：卖光（账本 0）也要按账本算——触发低库存建议且 currentQty=0
        commonStubs(Map.of());
        when(deviceInventoryRepository.findByIdDeviceId("EG-1"))
                .thenReturn(List.of(inv("100014", 1, 4, 1)));
        when(inventoryLotService.lotLedgerSkuIds("EG-1")).thenReturn(Set.of("100014"));
        when(inventoryLotService.sellableQuantity("EG-1", "100014")).thenReturn(0);

        List<ReplenishmentSuggestDto> out = service.suggestForDevice("EG-1");

        assertEquals(1, out.size());
        assertEquals(0, out.get(0).currentQty());
        assertEquals(4, out.get(0).suggestQty());
    }

    @Test
    void rop_suggestClampedByInventoryCapacity() {
        // ROP 目标 6 顶穿容量 4：建议钳到 4−2 = 2（补完正好满，不超放）
        when(inTransitService.qtyBySkuForDevice("EG-1")).thenReturn(Map.of());
        when(salesVelocityService.velocityBySku("EG-1")).thenReturn(Map.of(
                "100015", new SalesVelocityService.SkuVelocity(2, 3, 0.4, 3)));
        when(deviceInventoryRepository.findByIdDeviceId("EG-1"))
                .thenReturn(List.of(inv("100015", 2, 4, 1)));
        when(inventoryLotService.lotLedgerSkuIds("EG-1")).thenReturn(Set.of());
        when(deviceSlotService.hasSkuSlots(eq("EG-1"), anyString())).thenReturn(false);

        List<ReplenishmentSuggestDto> out = service.suggestForDevice("EG-1");

        assertEquals(1, out.size());
        assertEquals("ROP", out.get(0).suggestReason());
        assertTrue(out.get(0).suggestQty() <= 2,
                "建议 %d 超过容量4−账面2".formatted(out.get(0).suggestQty()));
        assertEquals(2, out.get(0).suggestQty());
    }

    @Test
    void mixed_skus_eachUsesItsOwnBookSource() {
        // 同柜混排：进过账本的按账本、没进过的按台账，互不串
        commonStubs(Map.of());
        when(deviceInventoryRepository.findByIdDeviceId("EG-1"))
                .thenReturn(List.of(inv("100013", 2, 4, 3), inv("100014", 1, 4, 1)));
        when(inventoryLotService.lotLedgerSkuIds("EG-1")).thenReturn(Set.of("100014"));
        when(inventoryLotService.sellableQuantity("EG-1", "100014")).thenReturn(1);

        List<ReplenishmentSuggestDto> out = service.suggestForDevice("EG-1");

        assertEquals(2, out.size());
        assertEquals(2, out.stream().filter(s -> s.skuId().equals("100013"))
                .findFirst().orElseThrow().suggestQty());
        assertEquals(3, out.stream().filter(s -> s.skuId().equals("100014"))
                .findFirst().orElseThrow().suggestQty());
    }

    @Test
    void slotPath_parBeyondInventoryCapacity_clampedToCapacityMinusBook() {
        // 用户实测案（货道页保存了 par=8 的货道，台账容量 4、账面 2）：
        // 建议不得按 PAR 顶到 8，必须钳到 4−2=2；currentQty 用台账口径而非货道账面 0
        when(inTransitService.qtyBySkuForDevice("EG-1")).thenReturn(Map.of());
        when(salesVelocityService.velocityBySku("EG-1")).thenReturn(
                Map.of("100013", new SalesVelocityService.SkuVelocity(2, 2, 0.3, 1)));
        when(deviceInventoryRepository.findByIdDeviceId("EG-1"))
                .thenReturn(List.of(inv("100013", 2, 4, 3)));
        when(inventoryLotService.lotLedgerSkuIds("EG-1")).thenReturn(Set.of());
        when(deviceSlotService.suggestSlotsForDevice("EG-1")).thenReturn(List.of(
                new com.aicabinet.common.dto.SlotReplenishmentSuggestDto(
                        "EG-1", "A1", "100013", 0, 8, 0, 8, 8, "LOW", 2, 1, "PAR+ROP")));

        List<ReplenishmentSuggestDto> out = service.suggestForDevice("EG-1");

        assertEquals(1, out.size());
        ReplenishmentSuggestDto s = out.get(0);
        assertEquals(2, s.currentQty(), "账面应为台账口径 2，不是货道账面 0");
        assertEquals(4, s.capacity(), "容量应为台账容量 4，不是货道 PAR 8");
        assertEquals(2, s.suggestQty(), "建议应钳到 4−2=2，不能按 PAR 补到 8");
    }
}
