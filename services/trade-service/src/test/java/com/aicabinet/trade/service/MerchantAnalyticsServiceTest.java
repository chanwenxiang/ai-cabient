package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantAnalyticsOverviewDto;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.common.dto.MerchantSkuPerformanceDto;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSlotMapper;
import com.aicabinet.trade.mapper.InventoryWriteOffMapper;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantAnalyticsServiceTest {

    @Mock PermissionService permissionService;
    @Mock MerchantPortalGuard merchantPortalGuard;
    @Mock MerchantFeaturePackService merchantFeaturePackService;
    @Mock CabinetOrderLineMapper lineRepository;
    @Mock CabinetOrderMapper orderRepository;
    @Mock InventoryWriteOffMapper writeOffRepository;
    @Mock PullOffTaskMapper pullOffTaskRepository;
    @Mock SalesVelocityService salesVelocityService;
    @Mock SkuCatalogMapper skuCatalogRepository;
    @Mock DeviceSkuInventoryMapper inventoryRepository;
    @Mock DeviceSlotMapper slotRepository;
    @Mock InventoryLotService inventoryLotService;
    @Mock CompetitiveGapService competitiveGapService;

    private MerchantAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new MerchantAnalyticsService(
                permissionService, merchantPortalGuard, merchantFeaturePackService,
                lineRepository, orderRepository, writeOffRepository, pullOffTaskRepository,
                salesVelocityService, skuCatalogRepository, inventoryRepository, slotRepository,
                inventoryLotService, competitiveGapService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void overview_usesBillableOrderCount_notZeroYuanOpens() {
        when(merchantFeaturePackService.allowedDeviceIdsForPack(1L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of("cab-1"));
        when(lineRepository.sumRevenueByDeviceIdsSince(any(), any())).thenReturn(800L);
        when(lineRepository.sumCogsByDeviceIdsSince(any(), any())).thenReturn(200L);
        when(writeOffRepository.sumCostCentsByDeviceIdsSince(any(), any())).thenReturn(0L);
        when(lineRepository.sumRevenueByDeviceIdsBetween(any(), any(), any())).thenReturn(0L);
        when(lineRepository.sumCogsByDeviceIdsBetween(any(), any(), any())).thenReturn(0L);
        when(orderRepository.countBillableByDeviceIdInAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(2L);
        when(orderRepository.sumTotalAmountByDeviceIdInSince(any(), any())).thenReturn(800L);
        List<Object[]> skuRows = new java.util.ArrayList<>();
        skuRows.add(new Object[]{"sku-1", "汽水", 2L, 800L, 200L});
        when(lineRepository.skuBreakdownByDevicesSince(any(), any())).thenReturn(skuRows);

        MerchantAnalyticsOverviewDto dto = service.overview(1L, 7);

        assertEquals(2, dto.orderCount());
        assertEquals(2, dto.itemQtySold());
        assertEquals(400, dto.avgOrderValueCents());
        assertEquals(800, dto.revenueCents());
    }

    @Test
    void salesReports_passesCabinetDimThrough() {
        when(merchantFeaturePackService.allowedDeviceIdsForPack(eq(1L), eq(MerchantFeaturePacks.BIZ)))
                .thenReturn(Set.of("cab-1"));
        when(competitiveGapService.salesReportForDevices(any(), eq("CABINET"), any(), any()))
                .thenReturn(List.of());

        assertEquals(List.of(), service.salesReports(1L, "CABINET", "2026-10-01", "2026-10-04"));
    }

    @Test
    void overview_topSkus_keepsDelistedSkusThatSold() {
        when(merchantFeaturePackService.allowedDeviceIdsForPack(1L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of("cab-1"));
        when(lineRepository.sumRevenueByDeviceIdsSince(any(), any())).thenReturn(1750L);
        when(lineRepository.sumCogsByDeviceIdsSince(any(), any())).thenReturn(0L);
        when(writeOffRepository.sumCostCentsByDeviceIdsSince(any(), any())).thenReturn(0L);
        when(lineRepository.sumRevenueByDeviceIdsBetween(any(), any(), any())).thenReturn(0L);
        when(lineRepository.sumCogsByDeviceIdsBetween(any(), any(), any())).thenReturn(0L);
        when(orderRepository.countBillableByDeviceIdInAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(4L);
        when(orderRepository.sumTotalAmountByDeviceIdInSince(any(), any())).thenReturn(1750L);
        List<Object[]> skuRows = new java.util.ArrayList<>();
        skuRows.add(new Object[]{"SKU-OFF", "纯牛奶 250ml", 3L, 1350L, 750L});
        skuRows.add(new Object[]{"SKU-ON", "汽水", 1L, 400L, 200L});
        when(lineRepository.skuBreakdownByDevicesSince(any(), any())).thenReturn(skuRows);
        SkuCatalog onCab = new SkuCatalog();
        onCab.setSkuId("SKU-ON");
        onCab.setSkuName("汽水");
        onCab.setSpec("330ml");
        when(skuCatalogRepository.findAllById(any())).thenReturn(List.of(onCab));

        MerchantAnalyticsOverviewDto dto = service.overview(1L, 7);

        assertEquals(2, dto.topSkus().size());
        assertEquals("SKU-OFF", dto.topSkus().get(0).skuId());
        assertEquals("SKU-ON", dto.topSkus().get(1).skuId());
        assertEquals("汽水 330ml", dto.topSkus().get(1).skuName());
        assertEquals(4, dto.orderCount());
    }

    @Test
    void overview_stockoutEstimate_skipsDelistedZeroStockSku() {
        when(merchantFeaturePackService.allowedDeviceIdsForPack(1L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of("cab-1"));
        when(lineRepository.sumRevenueByDeviceIdsSince(any(), any())).thenReturn(2150L);
        when(lineRepository.sumCogsByDeviceIdsSince(any(), any())).thenReturn(1150L);
        when(writeOffRepository.sumCostCentsByDeviceIdsSince(any(), any())).thenReturn(0L);
        when(lineRepository.sumRevenueByDeviceIdsBetween(any(), any(), any())).thenReturn(0L);
        when(lineRepository.sumCogsByDeviceIdsBetween(any(), any(), any())).thenReturn(0L);
        when(orderRepository.countBillableByDeviceIdInAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(5L);
        when(orderRepository.sumTotalAmountByDeviceIdInSince(any(), any())).thenReturn(2150L);
        List<Object[]> skuRows = new java.util.ArrayList<>();
        // 下架品清零后账面 0、窗口内仍卖过 ⇒ 不得计入「缺货 N 种 · 估损」
        skuRows.add(new Object[]{"SKU-DEL", "已下架汽水", 3L, 1350L, 750L});
        skuRows.add(new Object[]{"SKU-OOS", "在售汽水", 2L, 800L, 400L});
        when(lineRepository.skuBreakdownByDevicesSince(any(), any())).thenReturn(skuRows);
        DeviceSkuInventory delisted = new DeviceSkuInventory();
        delisted.setId(new DeviceSkuInventoryId("cab-1", "SKU-DEL"));
        delisted.setQuantity(0);
        DeviceSkuInventory oos = new DeviceSkuInventory();
        oos.setId(new DeviceSkuInventoryId("cab-1", "SKU-OOS"));
        oos.setQuantity(0);
        when(inventoryRepository.findByIdDeviceIdIn(any())).thenReturn(List.of(delisted, oos));
        when(inventoryLotService.deviceUsesLotLedger(any())).thenReturn(false);
        SkuCatalog delistedCatalog = activeSku("SKU-DEL");
        delistedCatalog.setStatus("INACTIVE");
        when(skuCatalogRepository.findAllById(any()))
                .thenReturn(List.of(activeSku("SKU-OOS"), delistedCatalog));

        MerchantAnalyticsOverviewDto dto = service.overview(1L, 7);

        assertEquals(1, dto.stockoutSkuCount());
        // 仅在售缺货 SKU 计损：日均 2/7 × 件均毛利 200 × 窗口 7 = 400
        assertEquals(400L, dto.stockoutLossEstimateCents());
        assertEquals(2, dto.topSkus().size());
    }

    @Test
    void skuPerformance_medianIgnoresOffCabinetSales() {
        when(merchantFeaturePackService.allowedDeviceIdsForPack(1L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of("cab-1"));
        List<Object[]> skuRows = new java.util.ArrayList<>();
        skuRows.add(new Object[]{"SKU-OFF", "已下架爆款", 10L, 5000L, 2000L});
        skuRows.add(new Object[]{"SKU-ON", "汽水", 3L, 1200L, 600L});
        when(lineRepository.skuBreakdownByDevicesSince(any(), any())).thenReturn(skuRows);
        DeviceSkuInventory stocked = new DeviceSkuInventory();
        stocked.setId(new DeviceSkuInventoryId("cab-1", "SKU-ON"));
        stocked.setQuantity(4);
        when(inventoryRepository.findByIdDeviceIdIn(any())).thenReturn(List.of(stocked));
        when(inventoryLotService.deviceUsesLotLedger(any())).thenReturn(false);
        when(slotRepository.listByDeviceIds(any())).thenReturn(List.of());
        when(skuCatalogRepository.findAllById(any())).thenReturn(List.of(activeSku("SKU-ON")));

        List<MerchantSkuPerformanceDto> rows = service.skuPerformance(1L, 7);

        assertEquals(1, rows.size());
        assertEquals("SKU-ON", rows.get(0).skuId());
        assertEquals("NORMAL", rows.get(0).performanceLevel());
    }

    @Test
    void skuDisplayName_appendsSpecWhenMissing() {
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId("SKU-1");
        sku.setSkuName("纯牛奶");
        sku.setSpec("250ml");
        assertEquals("纯牛奶 250ml", MerchantAnalyticsService.skuDisplayName(sku));
        sku.setSkuName("纯牛奶 250ml");
        assertEquals("纯牛奶 250ml", MerchantAnalyticsService.skuDisplayName(sku));
    }

    @Test
    void skuPerformance_ignoresSoldSkuNotOnCabinet() {
        when(merchantFeaturePackService.allowedDeviceIdsForPack(1L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of("cab-1"));
        List<Object[]> skuRows = new java.util.ArrayList<>();
        skuRows.add(new Object[]{"SKU-OFF", "纯牛奶 250ml", 3L, 1350L, 750L});
        when(lineRepository.skuBreakdownByDevicesSince(any(), any())).thenReturn(skuRows);
        DeviceSkuInventory stocked = new DeviceSkuInventory();
        stocked.setId(new DeviceSkuInventoryId("cab-1", "SKU-STOCK"));
        stocked.setQuantity(4);
        when(inventoryRepository.findByIdDeviceIdIn(any())).thenReturn(List.of(stocked));
        when(inventoryLotService.deviceUsesLotLedger(any())).thenReturn(false);
        when(slotRepository.listByDeviceIds(any())).thenReturn(List.of());
        when(skuCatalogRepository.findAllById(any())).thenReturn(List.of(activeSku("SKU-STOCK")));

        List<MerchantSkuPerformanceDto> rows = service.skuPerformance(1L, 7);

        assertEquals(1, rows.size());
        assertEquals("SKU-STOCK", rows.get(0).skuId());
        assertEquals(0, rows.get(0).qtySold());
    }

    @Test
    void skuPerformance_excludesDelistedSkuStillHoldingBookStock() {
        when(merchantFeaturePackService.allowedDeviceIdsForPack(1L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of("cab-1"));
        when(lineRepository.skuBreakdownByDevicesSince(any(), any())).thenReturn(List.of());
        DeviceSkuInventory delisted = new DeviceSkuInventory();
        delisted.setId(new DeviceSkuInventoryId("cab-1", "SKU-DEL"));
        delisted.setQuantity(4);
        DeviceSkuInventory stocked = new DeviceSkuInventory();
        stocked.setId(new DeviceSkuInventoryId("cab-1", "SKU-ON"));
        stocked.setQuantity(4);
        when(inventoryRepository.findByIdDeviceIdIn(any())).thenReturn(List.of(delisted, stocked));
        when(inventoryLotService.deviceUsesLotLedger(any())).thenReturn(false);
        when(slotRepository.listByDeviceIds(any())).thenReturn(List.of());
        SkuCatalog delistedCatalog = activeSku("SKU-DEL");
        delistedCatalog.setStatus("INACTIVE");
        when(skuCatalogRepository.findAllById(any())).thenReturn(List.of(activeSku("SKU-ON"), delistedCatalog));

        List<MerchantSkuPerformanceDto> rows = service.skuPerformance(1L, 7);

        assertEquals(1, rows.size());
        assertEquals("SKU-ON", rows.get(0).skuId());
    }

    private static SkuCatalog activeSku(String skuId) {
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId(skuId);
        sku.setSkuName(skuId);
        sku.setStatus("ACTIVE");
        return sku;
    }
}
