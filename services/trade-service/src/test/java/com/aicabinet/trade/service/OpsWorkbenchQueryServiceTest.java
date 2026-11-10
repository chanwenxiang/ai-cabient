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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    /**
     * B13 同族：运营工作台「待审核争议 / 设备离线」的**设备范围必须下推进 SQL**（不是取全局窗口再内存过滤）。
     * <p>旧写法是「取 CAP(20) 条全局最早/最近的记录，再 filter(scopedDevices)」——域受限账号（分仓/线长）
     * 自己的工单被别家的挤出窗口后，面板显示「没有待办」而工单还挂着；争议含资金、下架含履约。
     * <p>离线那处还多一维：`lifecycle=DEPLOYED` 也必须下推，否则窗口会被 INBOUND/退役柜占满。
     */
    @Test
    void collectDisputeAndOffline_pushesDeviceScopeIntoSql() {
        Set<String> scope = Set.of("EG-1");
        com.aicabinet.trade.domain.DisputeTicket ticket = new com.aicabinet.trade.domain.DisputeTicket();
        ticket.setTicketId("DT-1");
        ticket.setSessionId("S-1");
        ticket.setSessionDeviceId("EG-1");
        ticket.setReason("识别超时");
        ticket.setCreatedAt(Instant.now());
        when(disputeRepository.findByStatusAndDeviceIdsOrderByCreatedAtDesc("OPEN", scope, 20))
                .thenReturn(List.of(ticket));

        DeviceInfo offline = new DeviceInfo();
        offline.setDeviceId("EG-1");
        offline.setDeviceName("一号柜");
        offline.setUpdatedAt(Instant.now());
        when(deviceRepository.findDeployedByDeviceIdInAndOnlineStatusNotOrderByUpdatedAtAsc(
                eq(scope), eq("ONLINE"), eq(20))).thenReturn(List.of(offline));

        List<OpsActionItemDto> items = new ArrayList<>();
        service.collectDisputeActionItems(scope, items);
        service.collectOfflineDeviceItems(scope, items);

        verify(disputeRepository).findByStatusAndDeviceIdsOrderByCreatedAtDesc("OPEN", scope, 20);
        verify(deviceRepository).findDeployedByDeviceIdInAndOnlineStatusNotOrderByUpdatedAtAsc(scope, "ONLINE", 20);
        // deviceId 由联表回填，不该再逐行查 session
        verify(sessionRepository, never()).findById(anyString());
        assertTrue(items.stream().anyMatch(i -> "DISPUTE".equals(i.type()) && "EG-1".equals(i.deviceId())),
                "争议条目必须带出设备号：" + items);
        assertTrue(items.stream().anyMatch(i -> "DEVICE_OFFLINE".equals(i.type())));
    }

    /** 在途超时：列表与计数都必须把设备范围下推（旧写法各自取 500 行样本再内存过滤/去重）。 */
    @Test
    void collectInTransitOverdue_pushesDeviceScopeIntoSql() {
        Set<String> scope = Set.of("EG-1");
        when(inTransitRepository.findByStatusAndCreatedAtBeforeAndDeviceIdInOrderByCreatedAtAsc(
                eq("IN_TRANSIT"), any(Instant.class), eq(scope), eq(500)))
                .thenReturn(List.of(line(1L, 90L, "EG-1", "SKU-1", 3, Instant.now().minusSeconds(86400 * 3))));
        when(inTransitRepository.countDistinctOutboundOverdue(
                eq("IN_TRANSIT"), any(Instant.class), eq(scope))).thenReturn(1L);

        List<OpsActionItemDto> items = new ArrayList<>();
        service.collectInTransitOverdueItems(scope, items);

        verify(inTransitRepository).findByStatusAndCreatedAtBeforeAndDeviceIdInOrderByCreatedAtAsc(
                eq("IN_TRANSIT"), any(Instant.class), eq(scope), eq(500));
        assertEquals(1, service.countInTransitOverdue(scope));
        assertTrue(items.stream().anyMatch(i -> "IN_TRANSIT_OVERDUE".equals(i.type())),
                "超时在途必须产出告警：" + items);
    }

    /** 分账待跟进：每状态 Top-N 必须带设备范围（旧写法 window 全局最早 20 条，域外工单挤掉域内）。 */
    @Test
    void collectSplitException_pushesDeviceScopeIntoSql() {
        Set<String> scope = Set.of("EG-1");
        for (String status : OpsWorkbenchQueryService.SPLIT_EXCEPTION_STATUSES) {
            when(splitRepository.findTopNByStatusAndDeviceIdInOrderByCreatedAtAsc(status, scope, 20))
                    .thenReturn(List.of());
        }

        List<OpsActionItemDto> items = new ArrayList<>();
        service.collectSplitExceptionItems(scope, items);

        for (String status : OpsWorkbenchQueryService.SPLIT_EXCEPTION_STATUSES) {
            verify(splitRepository).findTopNByStatusAndDeviceIdInOrderByCreatedAtAsc(status, scope, 20);
        }
    }

    /**
     * 计数器同样必须下推：旧写法「取 500 条样本再内存过滤计数」在 OPEN 工单 / 超时行超过 500 时
     * **数字直接封顶**，面板上的数字本身就是错的（比列表少几条更误导）。
     */
    @Test
    void counts_pushScopeInsteadOfWindowedSample() {
        Set<String> scope = Set.of("EG-1");
        when(disputeRepository.countOpenByDeviceIds(scope)).thenReturn(7L);
        when(deviceRepository.countDeployedByDeviceIdInAndOnlineStatusNot(scope, "ONLINE")).thenReturn(3L);
        when(inTransitRepository.countDistinctOutboundOverdue(
                eq("IN_TRANSIT"), any(Instant.class), eq(scope))).thenReturn(5L);

        assertEquals(7, service.countOpenDisputes(scope));
        assertEquals(3, service.countOfflineDevices(scope));
        assertEquals(5, service.countInTransitOverdue(scope));

        // 空范围一律 0，且不得退化成「不过滤 = 全量泄漏」
        assertEquals(0, service.countOpenDisputes(Set.of()));
        assertEquals(0, service.countInTransitOverdue(Set.of()));
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
