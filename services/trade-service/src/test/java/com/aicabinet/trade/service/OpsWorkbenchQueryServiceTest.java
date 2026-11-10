package com.aicabinet.trade.service;

import com.aicabinet.common.dto.AdminStatsDto;
import com.aicabinet.common.dto.OpsActionItemDto;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.WarehouseInTransit;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PaymentReconciliationMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.WarehouseInTransitMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsWorkbenchQueryServiceTest {

    @Mock PermissionService permissionService;
    @Mock MerchantScopeService merchantScopeService;
    @Mock DeviceInfoMapper deviceRepository;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock CabinetOrderMapper orderRepository;
    @Mock DisputeTicketMapper disputeRepository;
    @Mock DisputeSlaService disputeSlaService;
    @Mock SlaMetricsService slaMetricsService;
    @Mock DeviceSkuInventoryMapper inventoryRepository;
    @Mock OrderRevenueSplitMapper splitRepository;
    @Mock InventoryLotService inventoryLotService;
    @Mock ReplenishmentTaskMapper replenishmentTaskRepository;
    @Mock PaymentReconciliationMapper reconciliationRepository;
    @Mock WarehouseInTransitMapper inTransitRepository;
    @Mock OpsExceptionMapper exceptionRepository;
    @Mock OpsSessionOrderQueryService sessionOrderQueryService;
    @Mock com.aicabinet.trade.mapper.SkuCatalogMapper skuCatalogRepository;

    private OpsWorkbenchQueryService service;

    @BeforeEach
    void setUp() {
        service = new OpsWorkbenchQueryService(
                permissionService, merchantScopeService, deviceRepository, sessionRepository,
                orderRepository, disputeRepository, disputeSlaService, slaMetricsService,
                inventoryRepository, splitRepository, inventoryLotService,
                replenishmentTaskRepository, reconciliationRepository, inTransitRepository,
                exceptionRepository, sessionOrderQueryService, skuCatalogRepository);
    }

    @Test
    void unpaidOverdueMinutes_alignedWithSessionOrderQuery() {
        assertEquals(30, OpsWorkbenchQueryService.UNPAID_OPS_OVERDUE_MINUTES);
        assertEquals(OpsSessionOrderQueryService.UNPAID_OPS_OVERDUE_MINUTES,
                OpsWorkbenchQueryService.UNPAID_OPS_OVERDUE_MINUTES);
        assertEquals(OpsSessionOrderQueryService.UNPAID_OPS_OVERDUE_MINUTES,
                AdminDashboardService.UNPAID_OPS_OVERDUE_MINUTES);
    }

    @Test
    void stats_whenDeviceScopeEmpty_returnsZeros() {
        when(merchantScopeService.allowedDeviceIds(42L)).thenReturn(Set.of());

        AdminStatsDto stats = service.stats(42L);

        assertEquals(0, stats.deviceTotal());
        assertEquals(0, stats.deviceOnline());
        assertEquals(0, stats.sessionActive());
        assertEquals(0, stats.orderToday());
        assertEquals(0, stats.revenueTodayCents());
        assertEquals(0, stats.disputeOpen());
        assertEquals(1.0, stats.doorSuccessRate24h(), 1e-9);
        assertEquals(1.0, stats.recognitionAutoRate24h(), 1e-9);
    }

    @Test
    void aggregateInTransitOverdue_sameOutboundOneItem() {
        Instant t0 = Instant.parse("2026-09-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-09-01T01:00:00Z");
        List<OpsActionItemDto> items = OpsWorkbenchQueryService.aggregateInTransitOverdueActionItems(List.of(
                line(1L, 2L, "CAB-001", "SKU-A", 3, t1),
                line(2L, 2L, "CAB-001", "SKU-B", 5, t0),
                line(3L, 2L, "CAB-001", "SKU-A", 1, t1)
        ));

        assertEquals(1, items.size());
        assertEquals("IN_TRANSIT_OVERDUE", items.get(0).type());
        assertEquals("HIGH", items.get(0).severity());
        assertTrue(items.get(0).detail().contains("共 9 件"));
    }

    @Test
    void isDeployedDevice_onlyDeployedLifecycle() {
        DeviceInfo deployed = new DeviceInfo();
        deployed.setLifecycleStatus("DEPLOYED");
        DeviceInfo inbound = new DeviceInfo();
        inbound.setLifecycleStatus("INBOUND");
        DeviceInfo blank = new DeviceInfo();
        blank.setLifecycleStatus(null);

        assertTrue(OpsWorkbenchQueryService.isDeployedDevice(deployed));
        assertTrue(!OpsWorkbenchQueryService.isDeployedDevice(inbound));
        // normalizeLifecycle(null) → DEPLOYED（与 DeviceAssetService 一致）
        assertTrue(OpsWorkbenchQueryService.isDeployedDevice(blank));
    }

    /**
     * 运营待办走台账口径（同商户端首页规则），与同页 lowStock 指标（countLowStock）同源。
     * 批 3b 第二十刀后全柜型单一口径（货道分支已删）。
     */
    @Test
    void collectLowStockItems_usesInventory_notSlots() {
        DeviceSkuInventory inv = new DeviceSkuInventory();
        inv.setDeviceId("EG-DOOR");
        inv.setSkuId("100013");
        inv.setQuantity(2);
        inv.setCapacity(4);
        inv.setLowThreshold(3);
        when(inventoryRepository.findLowStockByDeviceIds(anyList())).thenReturn(List.of(inv));
        when(skuCatalogRepository.findBySkuIds(anyList())).thenReturn(List.of());

        List<OpsActionItemDto> items = new ArrayList<>();
        service.collectLowStockItems(Set.of("EG-DOOR"), items);

        assertEquals(1, items.size());
        assertTrue(items.get(0).detail().contains("阈值 3 件"), items.get(0).detail());
    }

    /** 口径不再按柜型分流：历史货道柜同样走台账阈值。 */
    @Test
    void collectLowStockItems_legacyCabinet_usesLedgerToo() {
        DeviceSkuInventory inv = new DeviceSkuInventory();
        inv.setDeviceId("CAB-SLOT");
        inv.setSkuId("SKU-1");
        inv.setQuantity(0);
        inv.setCapacity(8);
        inv.setLowThreshold(4);
        when(inventoryRepository.findLowStockByDeviceIds(anyList())).thenReturn(List.of(inv));
        when(skuCatalogRepository.findBySkuIds(anyList())).thenReturn(List.of());

        List<OpsActionItemDto> items = new ArrayList<>();
        service.collectLowStockItems(Set.of("CAB-SLOT"), items);

        assertEquals(1, items.size());
        assertTrue(items.get(0).detail().contains("阈值 4 件"), items.get(0).detail());
        assertTrue(items.get(0).detail().indexOf("货道 ") < 0);
    }

    /**
     * 「补货占柜」必须与 {@code ReplenishmentTask#isRestockingNow} 同源：只算**已签到**的 IN_PROGRESS。
     * <p>出库明细生成即置 IN_PROGRESS 的行人还没到店，算进去会让运营看到虚高的占用数，
     * 也和「挡开门」的口径对不上（那边逐柜查的就是 isRestockingNow）。
     * 这条用例钉的是「取数走 findRestockingNowDeviceIds」——如果谁改回只按状态查窗口，
     * 这个 stub 就没人用了（严格桩直接报错），且占用数会变 0。
     */
    @Test
    void countOccupiedDevices_usesRestockingNowQuery() {
        when(sessionRepository.countDistinctDeviceIdByStateIn(anyList())).thenReturn(0L);
        when(replenishmentTaskRepository.findRestockingNowDeviceIds(null)).thenReturn(Set.of("EG-1", "EG-2"));

        assertEquals(2, service.countOccupiedDevices(null));
    }

    private static WarehouseInTransit line(Long transitId, Long outboundId, String deviceId,
                                           String skuId, int qty, Instant createdAt) {
        WarehouseInTransit t = new WarehouseInTransit();
        t.setTransitId(transitId);
        t.setOutboundId(outboundId);
        t.setDeviceId(deviceId);
        t.setSkuId(skuId);
        t.setQuantity(qty);
        t.setCreatedAt(createdAt);
        t.setStatus("IN_TRANSIT");
        return t;
    }
}
