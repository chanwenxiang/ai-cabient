package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设备补货表（{@code floor/getFloorByDevice} 数据源）单测，2026-10-21。
 * <p>核心契约：售价必须与结算同源（{@code resolveUnitPriceCents}），行集只收
 * ACTIVE 且 SKU 在册的映射，挂接 id 非数字/超 int 一律回 0（固件字段是 Java int）。</p>
 */
@ExtendWith(MockitoExtension.class)
class JiangyiFloorGoodsServiceTest {

    private static final String DEVICE = "D-1";

    @Mock private JiangyiClassMappingService classMappingService;
    @Mock private SkuCatalogMapper skuCatalogMapper;
    @Mock private JiangyiSkuJiangyiLinkMapper linkMapper;
    @Mock private DeviceSkuInventoryMapper inventoryMapper;
    @Mock private MerchantSkuPricingService pricingService;
    @Mock private JiangyiMappingReviewService mappingReviewService;

    @BeforeEach
    void verifiedByDefault() {
        // 其余用例都在「已按当前 classes 复核」的前提下考察行集；未复核闸口单列用例
        lenient().when(mappingReviewService.isVerified(DEVICE)).thenReturn(true);
    }

    private JiangyiFloorGoodsService service() {
        return new JiangyiFloorGoodsService(classMappingService, skuCatalogMapper, linkMapper,
                inventoryMapper, pricingService, mappingReviewService);
    }

    private JiangyiClassMapping mapping(Integer classId, String skuId, String status) {
        JiangyiClassMapping m = new JiangyiClassMapping();
        m.setDeviceId(DEVICE);
        m.setClassId(classId);
        m.setSkuId(skuId);
        m.setStatus(status);
        return m;
    }

    private SkuCatalog sku(String skuId, String name, String status) {
        SkuCatalog s = new SkuCatalog();
        s.setSkuId(skuId);
        s.setSkuName(name);
        s.setStatus(status);
        return s;
    }

    private DeviceSkuInventory inventory(String skuId, int quantity) {
        DeviceSkuInventory i = new DeviceSkuInventory();
        i.setDeviceId(DEVICE);
        i.setSkuId(skuId);
        i.setQuantity(quantity);
        return i;
    }

    private JiangyiSkuJiangyiLink link(String skuId, String productId) {
        JiangyiSkuJiangyiLink l = new JiangyiSkuJiangyiLink();
        l.setSkuId(skuId);
        l.setJiangyiProductId(productId);
        return l;
    }

    private void stubPricing() {
        when(pricingService.loadPromoPolicy()).thenReturn(PricingPromoPolicy.disabled());
        when(pricingService.resolveUnitPriceCents(eq(DEVICE), any(SkuCatalog.class), any()))
                .thenReturn(350);
    }

    @Test
    void rowCarriesThirdPidNamePriceAndStock() {
        when(classMappingService.listByDevice(DEVICE))
                .thenReturn(List.of(mapping(7, "SKU-7", "ACTIVE")));
        when(skuCatalogMapper.selectList(any())).thenReturn(List.of(sku("SKU-7", "可乐 330ml", "ACTIVE")));
        when(inventoryMapper.findByIdDeviceId(DEVICE)).thenReturn(List.of(
                inventory("SKU-7", 3), inventory("SKU-7", 2)));
        when(linkMapper.listBySku("SKU-7")).thenReturn(List.of(link("SKU-7", "100013")));
        stubPricing();

        List<JiangyiFloorGoodsService.FloorGoods> rows = service().listFloorGoods(DEVICE);

        assertEquals(1, rows.size());
        assertEquals(7, rows.get(0).classId());
        assertEquals(100013, rows.get(0).thirdPid());
        assertEquals("可乐 330ml", rows.get(0).productName());
        assertEquals(350, rows.get(0).salePrice());
        assertEquals(5, rows.get(0).stock());
    }

    @Test
    void inactiveAndUnmappedRowsNeverReachDevice() {
        when(classMappingService.listByDevice(DEVICE)).thenReturn(List.of(
                mapping(7, "SKU-7", "ACTIVE"),
                mapping(8, "SKU-8", "DISABLED"),
                mapping(9, null, "ACTIVE"),
                mapping(null, "SKU-10", "ACTIVE"),
                // 负编号 = 重算腾位哨兵（V400），即便被误激活也不得下发
                mapping(-7, "SKU-7", "ACTIVE"),
                mapping(11, "SKU-GONE", "ACTIVE")));
        // SKU-11 映射在册但商品目录查不到 → 下发出去设备会解析到一个我们认不出的商品
        when(skuCatalogMapper.selectList(any()))
                .thenReturn(List.of(sku("SKU-7", "可乐", "ACTIVE"), sku("SKU-8", "雪碧", "ACTIVE")));
        when(inventoryMapper.findByIdDeviceId(DEVICE)).thenReturn(List.of());
        when(linkMapper.listBySku("SKU-7")).thenReturn(List.of());
        stubPricing();

        List<JiangyiFloorGoodsService.FloorGoods> rows = service().listFloorGoods(DEVICE);

        assertEquals(1, rows.size());
        assertEquals(7, rows.get(0).classId());
        assertEquals(0, rows.get(0).stock());
    }

    @Test
    void delistedSkuIsSkipped() {
        when(classMappingService.listByDevice(DEVICE))
                .thenReturn(List.of(mapping(7, "SKU-7", "ACTIVE")));
        when(skuCatalogMapper.selectList(any())).thenReturn(List.of(sku("SKU-7", "可乐", "INACTIVE")));

        assertTrue(service().listFloorGoods(DEVICE).isEmpty());
        verify(linkMapper, never()).listBySku(anyString());
    }

    @Test
    void nonNumericAndOversizedProductIdFallBackToZeroThirdPid() {
        when(classMappingService.listByDevice(DEVICE)).thenReturn(List.of(
                mapping(7, "SKU-A", "ACTIVE"), mapping(8, "SKU-B", "ACTIVE"),
                mapping(9, "SKU-C", "ACTIVE"), mapping(10, "SKU-D", "ACTIVE")));
        when(skuCatalogMapper.selectList(any())).thenReturn(List.of(
                sku("SKU-A", "A", "ACTIVE"), sku("SKU-B", "B", "ACTIVE"),
                sku("SKU-C", "C", "ACTIVE"), sku("SKU-D", "D", "ACTIVE")));
        when(inventoryMapper.findByIdDeviceId(DEVICE)).thenReturn(List.of());
        when(linkMapper.listBySku("SKU-A")).thenReturn(List.of(link("SKU-A", "P-abc")));
        when(linkMapper.listBySku("SKU-B")).thenReturn(List.of(link("SKU-B", "3000000000")));
        when(linkMapper.listBySku("SKU-C")).thenReturn(List.of(link("SKU-C", "0")));
        when(linkMapper.listBySku("SKU-D")).thenReturn(List.of());
        when(pricingService.loadPromoPolicy()).thenReturn(PricingPromoPolicy.disabled());
        when(pricingService.resolveUnitPriceCents(eq(DEVICE), any(SkuCatalog.class), any())).thenReturn(100);

        List<JiangyiFloorGoodsService.FloorGoods> rows = service().listFloorGoods(DEVICE);

        assertEquals(4, rows.size());
        assertTrue(rows.stream().allMatch(r -> r.thirdPid() == 0), "非数字/超 int/≤0 一律回 0");
    }

    @Test
    void blankDeviceShortCircuits() {
        assertTrue(service().listFloorGoods(null).isEmpty());
        assertTrue(service().listFloorGoods("  ").isEmpty());
        verify(classMappingService, never()).listByDevice(any());
    }

    @Test
    void emptyMappingTableReturnsEmptyWithoutQueryingCatalog() {
        when(classMappingService.listByDevice(DEVICE)).thenReturn(List.of());

        assertTrue(service().listFloorGoods(DEVICE).isEmpty());
        verify(skuCatalogMapper, never()).selectList(any());
    }

    @Test
    void unverifiedMappingWithholdsEntireFloorTable() {
        // 重训后编号整体平移（H-27）：未按柜机当前 classes 复核前，一行都不下发
        when(mappingReviewService.isVerified(DEVICE)).thenReturn(false);

        assertTrue(service().listFloorGoods(DEVICE).isEmpty());
        verify(classMappingService, never()).listByDevice(any());
    }

    @Test
    void blankSkuNameFallsBackToSkuId() {
        when(classMappingService.listByDevice(DEVICE))
                .thenReturn(List.of(mapping(7, "SKU-7", "ACTIVE")));
        when(skuCatalogMapper.selectList(any())).thenReturn(List.of(sku("SKU-7", "  ", "ACTIVE")));
        when(inventoryMapper.findByIdDeviceId(DEVICE)).thenReturn(List.of());
        when(linkMapper.listBySku("SKU-7")).thenReturn(List.of());
        stubPricing();

        assertEquals("SKU-7", service().listFloorGoods(DEVICE).get(0).productName());
    }
}
