package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PurchaseSuggestionDto;
import com.aicabinet.trade.config.RopProperties;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.PurchaseOrderLineMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.SkuDelistReviewMapper;
import com.aicabinet.trade.mapper.WarehouseInventoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurchaseSuggestionServiceTest {

    @Mock private PermissionService permissionService;
    @Mock private CabinetOrderLineMapper orderLineRepository;
    @Mock private WarehouseInventoryMapper warehouseInventoryRepository;
    @Mock private PurchaseOrderLineMapper purchaseOrderLineRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private SkuDelistReviewMapper skuReviewRepository;

    private PurchaseSuggestionService service;

    @BeforeEach
    void setUp() {
        service = new PurchaseSuggestionService(permissionService, orderLineRepository,
                warehouseInventoryRepository, purchaseOrderLineRepository, skuCatalogRepository,
                skuReviewRepository, new RopProperties(), null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void suggest_shouldComputeQtyFromVelocityMinusOnHandAndPending() {
        when(orderLineRepository.sumSoldQtyAllSince(any()))
                .thenReturn(rows(new Object[]{"SKU-A", 14}, new Object[]{"SKU-B", 0}))
                .thenReturn(rows(new Object[]{"SKU-A", 20}, new Object[]{"SKU-B", 2}));
        when(warehouseInventoryRepository.sumQtyBySku(any()))
                .thenReturn(rows(new Object[]{"SKU-A", 5}));
        when(purchaseOrderLineRepository.pendingQtyBySku(any()))
                .thenReturn(rows(new Object[]{"SKU-A", 3}));
        when(skuCatalogRepository.findAllByOrderBySkuIdAsc())
                .thenReturn(List.of(sku("SKU-A", "可乐"), sku("SKU-B", "薯片")));

        List<PurchaseSuggestionDto> result = service.suggest(1L, null, 2, 14);

        assertEquals(2, result.size());
        // SKU-A: 日均 2.0，需求 2*(2+14)=32，建议 32-5-3=24
        PurchaseSuggestionDto a = result.get(0);
        assertEquals("SKU-A", a.skuId());
        assertEquals("可乐", a.skuName());
        assertEquals(24, a.suggestQty());
        assertEquals(5, a.onHandQty());
        assertEquals(3, a.pendingPoQty());
        assertEquals(14, a.soldQty7d());
        assertEquals(20, a.soldQty14d());
        assertEquals(16, a.coverageDays());
        assertEquals("SALES_DRIVEN", a.suggestReason());
        // SKU-B：日均 2/14，需求 ceil(2/14*16)=3
        assertEquals(3, result.get(1).suggestQty());
        verify(permissionService).requirePermission(1L, "ops:procurement:list");
    }

    @Test
    void suggest_shouldSkipSkuWithoutRecentSales() {
        when(orderLineRepository.sumSoldQtyAllSince(any()))
                .thenReturn(rows(new Object[]{"SKU-A", 7}))
                .thenReturn(rows());
        when(warehouseInventoryRepository.sumQtyBySku(any())).thenReturn(rows());
        when(purchaseOrderLineRepository.pendingQtyBySku(any())).thenReturn(rows());
        when(skuCatalogRepository.findAllByOrderBySkuIdAsc())
                .thenReturn(List.of(sku("SKU-A", "无动销商品")));

        List<PurchaseSuggestionDto> result = service.suggest(1L, "WH-001", 2, 14);

        // SKU-A 只有近 7 日销量、近 14 日无销量 → 不应建议补货
        assertTrue(result.isEmpty());
    }

    @Test
    void suggest_shouldSkipWhenStockCoversDemand() {
        when(orderLineRepository.sumSoldQtyAllSince(any()))
                .thenReturn(rows(new Object[]{"SKU-A", 7}))
                .thenReturn(rows(new Object[]{"SKU-A", 14}));
        when(warehouseInventoryRepository.sumQtyBySku(any()))
                .thenReturn(rows(new Object[]{"SKU-A", 100}));
        when(purchaseOrderLineRepository.pendingQtyBySku(any())).thenReturn(rows());
        when(skuCatalogRepository.findAllByOrderBySkuIdAsc())
                .thenReturn(List.of(sku("SKU-A", "可乐")));

        List<PurchaseSuggestionDto> result = service.suggest(1L, null, 2, 14);

        assertTrue(result.isEmpty());
    }

    @Test
    void suggest_shouldFilterByWarehouseWhenProvided() {
        when(orderLineRepository.sumSoldQtyAllSince(any()))
                .thenReturn(rows(new Object[]{"SKU-A", 7}))
                .thenReturn(rows(new Object[]{"SKU-A", 14}));
        when(warehouseInventoryRepository.sumQtyBySku(eq("WH-001"))).thenReturn(rows());
        when(purchaseOrderLineRepository.pendingQtyBySku(eq("WH-001"))).thenReturn(rows());
        when(skuCatalogRepository.findAllByOrderBySkuIdAsc())
                .thenReturn(List.of(sku("SKU-A", "可乐")));

        service.suggest(1L, "WH-001", 2, 14);

        verify(warehouseInventoryRepository).sumQtyBySku("WH-001");
        verify(purchaseOrderLineRepository).pendingQtyBySku("WH-001");
    }

    @Test
    void suggest_shouldUseTrendForecastWhenSalesRising() {
        int[] series = new int[28];
        for (int i = 0; i < 28; i++) {
            series[i] = i; // 0..27，近 7 日 21..27 共 168
        }
        when(orderLineRepository.sumSoldQtyAllSince(any()))
                .thenReturn(rows(new Object[]{"SKU-A", 168}))
                .thenReturn(rows(new Object[]{"SKU-A", 287}));
        when(orderLineRepository.soldQtyDailySince(any()))
                .thenReturn(dailyRows("SKU-A", series));
        when(warehouseInventoryRepository.sumQtyBySku(any())).thenReturn(rows());
        when(purchaseOrderLineRepository.pendingQtyBySku(any())).thenReturn(rows());
        when(skuCatalogRepository.findAllByOrderBySkuIdAsc())
                .thenReturn(List.of(sku("SKU-A", "可乐")));

        List<PurchaseSuggestionDto> result = service.suggest(1L, null, 2, 14);

        // 上升趋势：日均 24，斜率 1 件/天，覆盖 16 天 → 预测日均 25，需求 400；
        // 安全库存 = z(0.95)=1.645 × σ(近14日≈4.1833) × √2 ≈ 10
        assertEquals(1, result.size());
        PurchaseSuggestionDto a = result.get(0);
        assertEquals(410, a.suggestQty());
        assertEquals(10, a.safetyStockQty());
        assertEquals("TREND_FORECAST", a.suggestReason());
        assertEquals(25.0, a.forecastDailySales(), 0.001);
        assertEquals(1.0, a.trendPerDay(), 0.001);
        assertEquals(24.0, a.avgDailySales(), 0.001);
    }

    @Test
    void suggest_shouldReduceQtyWhenSalesFalling() {
        int[] series = new int[28];
        for (int i = 0; i < 28; i++) {
            series[i] = 27 - i; // 27..0，近 7 日 6..0 共 21，近 14 日共 91
        }
        when(orderLineRepository.sumSoldQtyAllSince(any()))
                .thenReturn(rows(new Object[]{"SKU-A", 21}))
                .thenReturn(rows(new Object[]{"SKU-A", 91}));
        when(orderLineRepository.soldQtyDailySince(any()))
                .thenReturn(dailyRows("SKU-A", series));
        when(warehouseInventoryRepository.sumQtyBySku(any())).thenReturn(rows());
        when(purchaseOrderLineRepository.pendingQtyBySku(any())).thenReturn(rows());
        when(skuCatalogRepository.findAllByOrderBySkuIdAsc())
                .thenReturn(List.of(sku("SKU-A", "可乐")));

        List<PurchaseSuggestionDto> result = service.suggest(1L, null, 2, 14);

        // 下降趋势：日均 3，斜率 -1 → 预测日均 2，需求 32 + 安全库存 10 = 42（低于销量驱动 48）
        assertEquals(1, result.size());
        PurchaseSuggestionDto a = result.get(0);
        assertEquals(42, a.suggestQty());
        assertEquals(10, a.safetyStockQty());
        assertEquals("TREND_FORECAST", a.suggestReason());
        assertEquals(2.0, a.forecastDailySales(), 0.001);
        assertEquals(-1.0, a.trendPerDay(), 0.001);
    }

    @Test
    void suggest_shouldStaySalesDrivenWhenTrendFlat() {
        int[] series = new int[28];
        java.util.Arrays.fill(series, 3);
        when(orderLineRepository.sumSoldQtyAllSince(any()))
                .thenReturn(rows(new Object[]{"SKU-A", 21}))
                .thenReturn(rows(new Object[]{"SKU-A", 42}));
        when(orderLineRepository.soldQtyDailySince(any()))
                .thenReturn(dailyRows("SKU-A", series));
        when(warehouseInventoryRepository.sumQtyBySku(any())).thenReturn(rows());
        when(purchaseOrderLineRepository.pendingQtyBySku(any())).thenReturn(rows());
        when(skuCatalogRepository.findAllByOrderBySkuIdAsc())
                .thenReturn(List.of(sku("SKU-A", "可乐")));

        List<PurchaseSuggestionDto> result = service.suggest(1L, null, 2, 14);

        assertEquals(1, result.size());
        PurchaseSuggestionDto a = result.get(0);
        assertEquals(48, a.suggestQty());
        assertEquals(0, a.safetyStockQty());
        assertEquals("SALES_DRIVEN", a.suggestReason());
        assertEquals(0.0, a.trendPerDay(), 0.001);
        assertEquals(3.0, a.forecastDailySales(), 0.001);
    }

    private static List<Object[]> dailyRows(String skuId, int... quantities) {
        List<Object[]> rows = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        int window = quantities.length;
        for (int i = 0; i < window; i++) {
            if (quantities[i] > 0) {
                rows.add(new Object[]{
                        skuId,
                        Date.valueOf(today.minusDays(window - 1L - i)),
                        quantities[i]
                });
            }
        }
        return rows;
    }

    private static SkuCatalog sku(String skuId, String skuName) {
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId(skuId);
        sku.setSkuName(skuName);
        return sku;
    }

    private static List<Object[]> rows(Object[]... data) {
        return List.of(data);
    }
}
