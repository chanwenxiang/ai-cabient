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
                skuReviewRepository, new RopProperties());
    }

    @Test
    void suggest_shouldComputeQtyFromVelocityMinusOnHandAndPending() {
        when(orderLineRepository.sumSoldQtyAllSince(any()))
                .thenReturn(rows(new Object[]{"SKU-A", 14}, new Object[]{"SKU-B", 0}),
                        rows(new Object[]{"SKU-A", 20}, new Object[]{"SKU-B", 2}));
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
                .thenReturn(rows(new Object[]{"SKU-A", 7}),
                        rows());
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
                .thenReturn(rows(new Object[]{"SKU-A", 7}),
                        rows(new Object[]{"SKU-A", 14}));
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
                .thenReturn(rows(new Object[]{"SKU-A", 7}),
                        rows(new Object[]{"SKU-A", 14}));
        when(warehouseInventoryRepository.sumQtyBySku(eq("WH-001"))).thenReturn(rows());
        when(purchaseOrderLineRepository.pendingQtyBySku(eq("WH-001"))).thenReturn(rows());
        when(skuCatalogRepository.findAllByOrderBySkuIdAsc())
                .thenReturn(List.of(sku("SKU-A", "可乐")));

        service.suggest(1L, "WH-001", 2, 14);

        verify(warehouseInventoryRepository).sumQtyBySku("WH-001");
        verify(purchaseOrderLineRepository).pendingQtyBySku("WH-001");
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
