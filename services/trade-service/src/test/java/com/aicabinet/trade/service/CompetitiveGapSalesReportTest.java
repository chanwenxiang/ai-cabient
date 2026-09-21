package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SalesReportRowDto;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceOpsEventMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.MerchantOpsConfigMapper;
import com.aicabinet.trade.mapper.MerchantRoleTemplateMapper;
import com.aicabinet.trade.mapper.OpsUserDeviceScopeMapper;
import com.aicabinet.trade.mapper.OpsUserDeviceScopePrefMapper;
import com.aicabinet.trade.mapper.OpsUserRouteScopeMapper;
import com.aicabinet.trade.mapper.PhoneVerifyLogMapper;
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
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** salesReport CATEGORY 维：商品行按 sku_catalog.category 归并、未知 SKU 归入「未分类」。 */
@ExtendWith(MockitoExtension.class)
class CompetitiveGapSalesReportTest {

    @Mock private OpsUserDeviceScopeMapper deviceScopeMapper;
    @Mock private OpsUserDeviceScopePrefMapper deviceScopePrefMapper;
    @Mock private MerchantOpsConfigMapper opsConfigMapper;
    @Mock private MerchantRoleTemplateMapper roleTemplateMapper;
    @Mock private DeviceOpsEventMapper deviceOpsEventMapper;
    @Mock private DeviceInfoMapper deviceInfoMapper;
    @Mock private PhoneVerifyLogMapper phoneVerifyLogMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private CabinetOrderMapper orderMapper;
    @Mock private CabinetOrderLineMapper lineMapper;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private DeviceSalesLockService salesLockService;
    @Mock private OpsUserRouteScopeMapper routeScopeMapper;
    @Mock private DistributedLockService distributedLockService;
    @Mock private SkuCatalogMapper skuCatalogMapper;

    private CompetitiveGapService service;

    @BeforeEach
    void setUp() {
        service = new CompetitiveGapService(deviceScopeMapper, deviceScopePrefMapper, opsConfigMapper,
                roleTemplateMapper, deviceOpsEventMapper, deviceInfoMapper, phoneVerifyLogMapper,
                merchantMapper, orderMapper, lineMapper, merchantScopeService, permissionService,
                auditService, salesLockService, new SecurityProperties(false),
                routeScopeMapper, distributedLockService, skuCatalogMapper, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    private static Object[] skuRow(String skuId, String skuName, long qty, long revenue, long cogs,
                                   long orderCount, long refunded, long refundOrders) {
        return new Object[]{skuId, skuName, qty, revenue, cogs, orderCount, refunded, refundOrders};
    }

    private static SkuCatalog sku(String skuId, String category) {
        SkuCatalog s = new SkuCatalog();
        s.setSkuId(skuId);
        s.setCategory(category);
        return s;
    }

    @Test
    void salesReport_categoryDim_mergesByCategoryAndSortsByRevenue() {
        when(merchantScopeService.allowedDeviceIds(1L)).thenReturn(null);
        when(lineMapper.skuBreakdownBetween(any(), any())).thenReturn(List.of(
                skuRow("SKU-A", "可乐", 2, 500, 300, 2, 0, 0),
                skuRow("SKU-B", "薯片", 1, 800, 500, 1, 0, 0),
                skuRow("SKU-D", "奶茶", 1, 200, 150, 1, 50, 1)
        ));
        when(skuCatalogMapper.findAllByOrderBySkuIdAsc()).thenReturn(List.of(
                sku("SKU-A", "饮料"),
                sku("SKU-B", "零食"),
                sku("SKU-D", "饮料")
        ));

        List<SalesReportRowDto> rows =
                service.salesReport(1L, "category", "2026-09-01", "2026-09-07");

        assertEquals(2, rows.size());
        assertEquals("零食", rows.get(0).dimKey());
        assertEquals(800, rows.get(0).revenueCents());
        assertEquals("饮料", rows.get(1).dimKey());
        assertEquals(700, rows.get(1).revenueCents());
        assertEquals(3, rows.get(1).qty());
        assertEquals(3, rows.get(1).orderCount());
        assertEquals(450, rows.get(1).cogsCents());
        assertEquals(50, rows.get(1).refundedCents());
        assertEquals(1, rows.get(1).refundOrderCount());
        assertEquals(250, rows.get(1).marginCents());
        assertEquals(rows.get(1).dimKey(), rows.get(1).dimLabel());
    }

    @Test
    void salesReport_categoryDim_unknownSkuFallsBackToUncategorized() {
        when(merchantScopeService.allowedDeviceIds(1L)).thenReturn(null);
        when(lineMapper.skuBreakdownBetween(any(), any())).thenReturn(
                java.util.Collections.singletonList(
                        skuRow("SKU-C", "咖啡", 1, 300, 200, 1, 0, 0)));
        when(skuCatalogMapper.findAllByOrderBySkuIdAsc()).thenReturn(List.<SkuCatalog>of());

        List<SalesReportRowDto> rows =
                service.salesReport(1L, "CATEGORY", "2026-09-01", "2026-09-07");

        assertEquals(1, rows.size());
        assertEquals("未分类", rows.get(0).dimKey());
        assertEquals(300, rows.get(0).revenueCents());
        assertEquals(100, rows.get(0).marginCents());
    }

    @Test
    void salesReportForDevices_categoryDim_aggregatesWithinScopedDevices() {
        when(lineMapper.skuBreakdownByDevicesBetween(anySet(), any(), any())).thenReturn(
                java.util.Collections.singletonList(
                        skuRow("SKU-A", "可乐", 1, 500, 300, 1, 0, 0)));
        when(skuCatalogMapper.findAllByOrderBySkuIdAsc()).thenReturn(List.of(
                sku("SKU-A", "饮料")
        ));

        List<SalesReportRowDto> rows = service.salesReportForDevices(
                java.util.Set.of("CAB-001"), "CATEGORY", "2026-09-01", "2026-09-07");

        assertEquals(1, rows.size());
        assertEquals("饮料", rows.get(0).dimKey());
        assertEquals(500, rows.get(0).revenueCents());
        assertTrue(rows.get(0).marginCents() == 200);
    }
}
