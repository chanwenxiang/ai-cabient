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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    /**
     * B13 同族：补货待办的**设备范围必须下推进 SQL**。
     * <p>旧写法「取全库最早 50 条再 `filter(inDeviceScope)`」的窗口是全局按 createdAt 取的，
     * 域受限账号（分仓 / 线长）自己的任务排在第 51 条之后就会被滤空 —— 面板显示「没有待办」，
     * 任务却实实在在挂着。这条用例钉的是「传给仓储的是本账号的范围」，不是「过滤结果对不对」。
     */
    @Test
    void appendReplenishmentItems_pushesDeviceScopeIntoSql() {
        when(merchantFeaturePackService.allowedDeviceIdsForPack(9L, MerchantFeaturePacks.FIELD))
                .thenReturn(Set.of("EG-1"));
        com.aicabinet.trade.domain.ReplenishmentTask task =
                new com.aicabinet.trade.domain.ReplenishmentTask();
        task.setTaskId(77L);
        task.setDeviceId("EG-1");
        task.setStatus("IN_PROGRESS");
        task.setCreatedAt(java.time.Instant.now());
        when(replenishmentTaskRepository.findWorkbenchTasks(
                anyList(), org.mockito.ArgumentMatchers.eq(Set.of("EG-1")),
                org.mockito.ArgumentMatchers.eq(50)))
                .thenReturn(List.of(task));

        MerchantWorkbenchDto wb = service.getWorkbench(9L);

        verify(replenishmentTaskRepository).findWorkbenchTasks(
                anyList(), org.mockito.ArgumentMatchers.eq(Set.of("EG-1")),
                org.mockito.ArgumentMatchers.eq(50));
        assertTrue(wb.actionItems().stream()
                        .anyMatch(i -> "REPLENISHMENT".equals(i.type()) && "EG-1".equals(i.deviceId())),
                "补货待办必须出现在工作台：设备范围已下推 SQL，结果不再需要内存过滤");
    }

    /**
     * B13 同族：争议 / 离线柜 / 临期下架三处的**设备范围都必须下推进 SQL**。
     *
     * <p>旧写法都是「取全局窗口（CAP=50，按 createdAt/updatedAt 全局排）再 `filter(inDeviceScope)`」——
     * 域受限账号（分仓/线长/单商户）自己的条目排在窗口外就一条都不返回，面板显示「没有待办」而
     * 工单还挂着。争议与下架是**含资金/含履约**的待办，漏看比补货更贵。
     *
     * <p>本条同时钉住「争议的 deviceId 来自联表回填的瞬态字段」——若回退成逐行
     * `sessionRepository.findById`，`never()` 会立刻红（N+1 回归即失败）。
     */
    @Test
    void workbench_pushesDeviceScopeForDisputeOfflineAndExpiry() {
        Set<String> scope = Set.of("EG-1");
        when(merchantFeaturePackService.allowedDeviceIdsForPack(9L, MerchantFeaturePacks.FIELD))
                .thenReturn(scope);
        when(merchantFeaturePackService.allowedDeviceIdsForPack(9L, MerchantFeaturePacks.BIZ))
                .thenReturn(scope);
        when(merchantFeaturePackService.allowedMerchantIdsForPack(9L, MerchantFeaturePacks.BIZ))
                .thenReturn(Set.of("MCH-1"));
        when(splitRepository.countByMerchantIdInAndStatusIn(any(), any())).thenReturn(0L);
        when(orderRepository.selectCount(any())).thenReturn(0L);

        com.aicabinet.trade.domain.DisputeTicket ticket = new com.aicabinet.trade.domain.DisputeTicket();
        ticket.setTicketId("DT-1");
        ticket.setSessionId("S-1");
        ticket.setSessionDeviceId("EG-1");
        ticket.setReason("识别超时");
        ticket.setCreatedAt(java.time.Instant.now());
        when(disputeRepository.findByStatusAndDeviceIdsOrderByCreatedAtDesc(
                eq("OPEN"), eq(scope), eq(50))).thenReturn(List.of(ticket));

        com.aicabinet.trade.domain.DeviceInfo offline = new com.aicabinet.trade.domain.DeviceInfo();
        offline.setDeviceId("EG-1");
        offline.setDeviceName("一号柜");
        offline.setUpdatedAt(java.time.Instant.now());
        when(deviceRepository.findByDeviceIdInAndOnlineStatusNotOrderByUpdatedAtAsc(
                eq(scope), eq("ONLINE"), eq(50))).thenReturn(List.of(offline));

        com.aicabinet.trade.domain.PullOffTask task = new com.aicabinet.trade.domain.PullOffTask();
        task.setTaskId(5L);
        task.setDeviceId("EG-1");
        task.setSkuId("SKU-1");
        task.setReason("临期");
        task.setCreatedAt(java.time.Instant.now());
        when(pullOffTaskRepository.findByStatusAndDeviceIdInOrderByCreatedAtDesc(
                eq("OPEN"), eq(scope), eq(50))).thenReturn(List.of(task));

        MerchantWorkbenchDto wb = service.getWorkbench(9L);

        verify(disputeRepository).findByStatusAndDeviceIdsOrderByCreatedAtDesc("OPEN", scope, 50);
        verify(deviceRepository).findByDeviceIdInAndOnlineStatusNotOrderByUpdatedAtAsc(scope, "ONLINE", 50);
        verify(pullOffTaskRepository).findByStatusAndDeviceIdInOrderByCreatedAtDesc("OPEN", scope, 50);
        verify(sessionRepository, never()).findById(anyString());

        assertTrue(wb.actionItems().stream()
                        .anyMatch(i -> "DISPUTE".equals(i.type()) && "EG-1".equals(i.deviceId())),
                "争议的 deviceId 应由联表回填，工作台不该再自己查 session：" + wb.actionItems());
        assertTrue(wb.actionItems().stream().anyMatch(i -> "DEVICE_OFFLINE".equals(i.type())));
        assertTrue(wb.actionItems().stream().anyMatch(i -> "EXPIRY".equals(i.type())));
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
