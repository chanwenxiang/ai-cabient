package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantWorkbenchDto;
import com.aicabinet.common.dto.OpsActionItemDto;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantWorkbenchQueryServiceTest {

    @Mock PermissionService permissionService;
    @Mock MerchantPortalGuard merchantPortalGuard;
    @Mock MerchantFeaturePackService merchantFeaturePackService;
    @Mock DeviceInfoMapper deviceRepository;
    @Mock CabinetOrderMapper orderRepository;
    @Mock OrderRevenueSplitMapper splitRepository;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock DisputeTicketMapper disputeRepository;
    @Mock DeviceSkuInventoryMapper inventoryRepository;
    @Mock PullOffTaskMapper pullOffTaskRepository;
    @Mock ReplenishmentTaskMapper replenishmentTaskRepository;
    @Mock com.aicabinet.trade.mapper.SkuCatalogMapper skuCatalogRepository;

    private MerchantWorkbenchQueryService service;

    @BeforeEach
    void setUp() {
        service = new MerchantWorkbenchQueryService(
                permissionService, merchantPortalGuard, merchantFeaturePackService,
                deviceRepository, orderRepository, splitRepository, sessionRepository,
                disputeRepository, inventoryRepository, pullOffTaskRepository,
                replenishmentTaskRepository, skuCatalogRepository);
    }

    @Test
    void g1_pendingSplitStatuses_differFromOpsWorkbench_alignWithMerchantFinance() {
        List<String> merchant = MerchantWorkbenchQueryService.PENDING_SPLIT_STATUSES;
        List<String> ops = OpsWorkbenchQueryService.PENDING_SPLIT_STATUSES;

        assertTrue(merchant.contains("PENDING"));
        assertTrue(merchant.contains("WECHAT_SUBMITTED"));
        assertTrue(merchant.contains("SUBMITTED"));
        assertFalse(merchant.contains("FAILED"));
        assertFalse(merchant.contains("WECHAT_FAILED"));

        assertFalse(ops.contains("PENDING"));
        assertTrue(ops.contains("FAILED") || ops.contains("WECHAT_FAILED")
                || ops.stream().anyMatch(s -> s.contains("FAILED")));
        assertTrue(ops.contains("ACCRUED"));

        // 与商户财务结算 overview 同源口径
        assertEquals(
                List.of("PENDING", "ACCRUED", "LEDGER_ONLY", "WECHAT_SUBMITTED", "SUBMITTED"),
                merchant);
    }

    @Test
    void getWorkbench_whenAllScopesEmpty_returnsZeros() {
        when(merchantFeaturePackService.allowedDeviceIdsForPack(9L, MerchantFeaturePacks.FIELD))
                .thenReturn(Set.of());
        when(merchantFeaturePackService.allowedDeviceIdsForPack(9L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of());
        when(merchantFeaturePackService.allowedMerchantIdsForPack(9L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of());

        MerchantWorkbenchDto wb = service.getWorkbench(9L);

        assertEquals(0, wb.openDisputes());
        assertEquals(0, wb.offlineDevices());
        assertEquals(0, wb.lowStockItems());
        assertEquals(0, wb.pendingSplits());
        assertTrue(wb.actionItems().isEmpty());
    }

    /**
     * 首页「库存偏低」走台账口径 {@code quantity <= low_threshold}（后台阈值），不拿 device_slot 凑数。
     * 事故背景（2026-10-28）：EG943322621391 残留 6 条陈旧货道 ⇒ 首页显示「库存偏低 6」，台账真值 3 条。
     * 批 3b 第二十刀后全柜型单一口径（货道分支已删）。
     */
    @Test
    void appendLowStockItems_usesInventoryThreshold_notResidualSlots() {
        when(inventoryRepository.findLowStockByDeviceIds(anyList()))
                .thenReturn(List.of(
                        inventory("EG-DOOR", "100013", 2, 4, 3),
                        inventory("EG-DOOR", "100014", 1, 4, 1)));
        when(skuCatalogRepository.findBySkuIds(anyList()))
                .thenReturn(List.of(catalog("100013", "ACTIVE"), catalog("100014", "ACTIVE")));

        List<OpsActionItemDto> items = new java.util.ArrayList<>();
        long n = service.appendLowStockItems(items, Set.of("EG-DOOR"));

        assertEquals(2, n);
        assertEquals(2, items.size());
        assertTrue(items.stream().noneMatch(i -> i.detail().startsWith("货道 ")),
                "不应产生货道口径条目：" + items);
        assertTrue(items.get(0).detail().contains("阈值 3 件"));
    }

    /**
     * 低库存面只算在架 SKU（2026-10-30 拍板）：下架行清零后账面 0/低于阈值，
     * 不过滤就会替商户催它已经放弃的商品（首页计数 + 低库存提醒同源）。
     */
    @Test
    void appendLowStockItems_skipsDelistedSkus() {
        when(inventoryRepository.findLowStockByDeviceIds(anyList()))
                .thenReturn(List.of(
                        inventory("EG-DOOR", "100013", 0, 4, 2),
                        inventory("EG-DOOR", "100016", 0, 4, 2)));
        when(skuCatalogRepository.findBySkuIds(anyList()))
                .thenReturn(List.of(catalog("100013", "ACTIVE"), catalog("100016", "INACTIVE")));

        List<OpsActionItemDto> items = new java.util.ArrayList<>();
        long n = service.appendLowStockItems(items, Set.of("EG-DOOR"));

        assertEquals(1, n);
        assertEquals(1, items.size());
        assertEquals("100013", items.get(0).skuId());
    }

    /** 口径不再按柜型分流：非开门柜（历史货道柜）同样走台账阈值，不再读 device_slot。 */
    @Test
    void appendLowStockItems_legacyCabinet_usesLedgerToo() {
        when(inventoryRepository.findLowStockByDeviceIds(anyList()))
                .thenReturn(List.of(inventory("CAB-SLOT", "SKU-1", 0, 8, 4)));
        when(skuCatalogRepository.findBySkuIds(anyList()))
                .thenReturn(List.of(catalog("SKU-1", "ACTIVE")));

        List<OpsActionItemDto> items = new java.util.ArrayList<>();
        long n = service.appendLowStockItems(items, Set.of("CAB-SLOT"));

        assertEquals(1, n);
        assertEquals("库存偏低", items.get(0).title());
        assertTrue(items.get(0).detail().contains("阈值 4 件"), items.get(0).detail());
        assertTrue(items.get(0).detail().indexOf("货道 ") < 0);
    }

    private static com.aicabinet.trade.domain.DeviceSkuInventory inventory(
            String deviceId, String skuId, int qty, int capacity, int lowThreshold) {
        com.aicabinet.trade.domain.DeviceSkuInventory inv = new com.aicabinet.trade.domain.DeviceSkuInventory();
        inv.setDeviceId(deviceId);
        inv.setSkuId(skuId);
        inv.setQuantity(qty);
        inv.setCapacity(capacity);
        inv.setLowThreshold(lowThreshold);
        return inv;
    }

    private static SkuCatalog catalog(String skuId, String status) {
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId(skuId);
        sku.setSkuName(skuId + "-名称");
        sku.setStatus(status);
        return sku;
    }
}
