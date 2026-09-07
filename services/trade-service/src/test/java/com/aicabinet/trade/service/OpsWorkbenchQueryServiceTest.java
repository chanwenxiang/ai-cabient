package com.aicabinet.trade.service;

import com.aicabinet.common.dto.AdminStatsDto;
import com.aicabinet.common.dto.OpsActionItemDto;
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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @Mock DeviceSlotService deviceSlotService;
    @Mock ReplenishmentTaskMapper replenishmentTaskRepository;
    @Mock PaymentReconciliationMapper reconciliationRepository;
    @Mock WarehouseInTransitMapper inTransitRepository;
    @Mock OpsExceptionMapper exceptionRepository;
    @Mock OpsSessionOrderQueryService sessionOrderQueryService;

    private OpsWorkbenchQueryService service;

    @BeforeEach
    void setUp() {
        service = new OpsWorkbenchQueryService(
                permissionService, merchantScopeService, deviceRepository, sessionRepository,
                orderRepository, disputeRepository, disputeSlaService, slaMetricsService,
                inventoryRepository, splitRepository, inventoryLotService, deviceSlotService,
                replenishmentTaskRepository, reconciliationRepository, inTransitRepository,
                exceptionRepository, sessionOrderQueryService);
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
