package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantWorkbenchDto;
import com.aicabinet.common.dto.OpsActionItemDto;
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
    @Mock DeviceSlotService deviceSlotService;
    @Mock ReplenishmentTaskMapper replenishmentTaskRepository;
    @Mock com.aicabinet.trade.mapper.SkuCatalogMapper skuCatalogRepository;

    private MerchantWorkbenchQueryService service;

    @BeforeEach
    void setUp() {
        service = new MerchantWorkbenchQueryService(
                permissionService, merchantPortalGuard, merchantFeaturePackService,
                deviceRepository, orderRepository, splitRepository, sessionRepository,
                disputeRepository, inventoryRepository, pullOffTaskRepository,
                deviceSlotService, replenishmentTaskRepository, skuCatalogRepository);
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
     * 开门柜（将邑绑定）没有货道概念：首页「库存偏低」必须走台账口径
     * {@code quantity <= low_threshold}（后台阈值），不得再拿 device_slot 残留行凑数。
     * 事故背景（2026-10-28）：EG943322621391 残留 6 条陈旧货道 ⇒ 首页显示「库存偏低 6」，
     * 台账真值只有 3 条。
     */
    @Test
    void appendLowStockItems_doorCabinet_usesInventoryThreshold_notResidualSlots() {
        when(deviceSlotService.isDoorCabinet("EG-DOOR")).thenReturn(true);
        when(inventoryRepository.findLowStockByDeviceIds(anyList()))
                .thenReturn(List.of(
                        inventory("EG-DOOR", "100013", 2, 4, 3),
                        inventory("EG-DOOR", "100014", 1, 4, 1)));
        when(skuCatalogRepository.findBySkuIds(anyList())).thenReturn(List.of());

        List<OpsActionItemDto> items = new java.util.ArrayList<>();
        long n = service.appendLowStockItems(items, Set.of("EG-DOOR"));

        assertEquals(2, n);
        assertEquals(2, items.size());
        assertTrue(items.stream().noneMatch(i -> i.detail().startsWith("货道 ")),
                "开门柜不应产生货道口径条目：" + items);
        assertTrue(items.get(0).detail().contains("阈值 3 件"));
        verify(deviceSlotService, never()).listShortageSlots(anyString());
    }

    /** 有货道柜保持原货道口径（不能因为开门柜改造把有货道柜也切到台账）。 */
    @Test
    void appendLowStockItems_slotCabinet_keepsSlotScope() {
        when(deviceSlotService.isDoorCabinet("CAB-SLOT")).thenReturn(false);
        when(deviceSlotService.listShortageSlots("CAB-SLOT")).thenReturn(List.of(
                new DeviceSlotService.SlotShortageItem("CAB-SLOT", "A1", "SKU-1", 0, 2, 8, 8, 50)));

        List<OpsActionItemDto> items = new java.util.ArrayList<>();
        long n = service.appendLowStockItems(items, Set.of("CAB-SLOT"));

        assertEquals(1, n);
        assertEquals("货道 A1 当前 0 / 容量 8（阈值 50%）", items.get(0).detail());
        verify(inventoryRepository, never()).findLowStockByDeviceIds(anyList());
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
}
