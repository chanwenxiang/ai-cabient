package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceInventoryDto;
import com.aicabinet.common.dto.MerchantShortageSummaryDto;
import com.aicabinet.common.dto.OpsActionItemDto;
import com.aicabinet.common.dto.ReplenishmentSuggestDto;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuLotMapper;
import com.aicabinet.trade.mapper.DeviceSlotMapper;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.mapper.ReplenishmentRouteMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskLineMapper;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.mapper.WarehouseInboundLineMapper;
import com.aicabinet.trade.mapper.WarehouseInboundMapper;
import com.aicabinet.trade.mapper.WarehouseInventoryMapper;
import com.aicabinet.trade.mapper.WarehouseManagerMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import com.aicabinet.trade.mapper.WarehouseMovementMapper;
import com.aicabinet.trade.mapper.WarehouseOutboundLineMapper;
import com.aicabinet.trade.mapper.WarehouseOutboundMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
import com.aicabinet.trade.support.SkuStockLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CB-076 门禁：开门柜（判据=将邑绑定）「缺货/低库存」必须全站同一个判定 + 同一个数量源。
 *
 * <p>此前同一个柜在同一时刻会得出不同结论：后台设备详情 KPI 读台账 quantity、商户柜机卡片恒 0、
 * 商户库存列表按数量源 A 判、备货建议按账本可售判。这里用**同一份夹具**分别喂五个读面，
 * 断言「需要补货的 SKU 集合」与「可售量」完全一致 —— 任一面将来改判据/改数量源即红。
 *
 * <p>夹具：4 个在架 SKU，其中 SKU-B 账面 3 但批次已被扣光（可售 0）。
 * <pre>
 *   SKU  台账quantity  批次可售  容量  阈值   期望档位
 *   A    0             —         4     2      OOS（缺货）
 *   B    3             0         4     1      OOS（缺货，账本为准）
 *   C    4             —         4     2      OK
 *   D    1             —         4     2      LOW（低库存）
 * </pre>
 * 需要补货 = {A, B, D}；缺口 = Σmax(容量−可售,0) = 4+4+3 = 11。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DoorCabinetStockLevelConsistencyTest {

    private static final String DEV = "EG-DOOR";
    private static final List<String> WARN_SKUS = List.of("SKU-A", "SKU-B", "SKU-D");

    // ── 夹具 ────────────────────────────────────────────────────────────────
    private static DeviceSkuInventory inv(String sku, int qty, int cap, int low) {
        DeviceSkuInventory row = new DeviceSkuInventory();
        row.setDeviceId(DEV);
        row.setSkuId(sku);
        row.setQuantity(qty);
        row.setCapacity(cap);
        row.setLowThreshold(low);
        return row;
    }

    private static List<DeviceSkuInventory> inventoryRows() {
        return List.of(inv("SKU-A", 0, 4, 2), inv("SKU-B", 3, 4, 1), inv("SKU-C", 4, 4, 2),
                inv("SKU-D", 1, 4, 2));
    }

    private static List<SkuCatalog> activeCatalog() {
        return List.of("SKU-A", "SKU-B", "SKU-C", "SKU-D").stream().map(sku -> {
            SkuCatalog c = new SkuCatalog();
            c.setSkuId(sku);
            c.setSkuName(sku + "-名称");
            c.setStatus("ACTIVE");
            return c;
        }).toList();
    }

    private static DeviceInfo doorDevice() {
        DeviceInfo d = new DeviceInfo();
        d.setDeviceId(DEV);
        d.setDeviceName("开门柜-测试");
        return d;
    }

    private static InventoryLotService lotLedger() {
        InventoryLotService lots = mock(InventoryLotService.class);
        when(lots.lotLedgerSkuIds(DEV)).thenReturn(Set.of("SKU-B"));
        when(lots.sellableQtyBySku(DEV)).thenReturn(Map.of("SKU-B", 0));
        when(lots.sellableQuantity(DEV, "SKU-B")).thenReturn(0);
        return lots;
    }

    private static String devicesXml() throws Exception {
        Path xml = Path.of(System.getProperty("user.dir"),
                "src/main/resources/mapper/DeviceSkuInventoryMapper.xml");
        return Files.readString(xml, StandardCharsets.UTF_8);
    }

    // ══ 1. 判据本体 ═════════════════════════════════════════════════════════
    @Test
    void skuStockLevel_twoTierBoundaryIsStrict() {
        assertEquals(SkuStockLevel.OOS, SkuStockLevel.of(0, 2));
        assertEquals(SkuStockLevel.OOS, SkuStockLevel.of(-3, 2), "负可售同样是缺货");
        assertEquals(SkuStockLevel.LOW, SkuStockLevel.of(1, 2));
        assertEquals(SkuStockLevel.LOW, SkuStockLevel.of(2, 2), "边界取严：等于阈值算低库存");
        assertEquals(SkuStockLevel.OK, SkuStockLevel.of(3, 2));
        assertEquals(SkuStockLevel.OOS, SkuStockLevel.of(0, 0));
        assertEquals(SkuStockLevel.OK, SkuStockLevel.of(1, 0), "阈值 0 时只有 0 才是缺货");
        assertEquals(SkuStockLevel.OK, SkuStockLevel.of(1, -5), "负阈值按 0 处理（只有 0 才是缺货）");
        assertTrue(SkuStockLevel.OOS.warning());
        assertTrue(SkuStockLevel.LOW.warning());
        assertFalse(SkuStockLevel.OK.warning());
    }

    // ══ 2. 后台设备详情 KPI ↔ 商户柜机卡片（同一个 doorCabinetStockStats）═══
    @Test
    void deviceDetailKpi_andMerchantDeviceCard_agree() {
        DeviceSlotService svc = doorSlotService();
        DeviceSlotService.SlotStockCounts card = svc.countSlotStock(DEV);
        var metrics = svc.getDeviceDetail(1L, DEV).metrics();

        assertEquals(2, card.oosSlotCount(), "商户卡片：缺货 2（A 卖光 + B 批次扣光）");
        assertEquals(1, card.lowStockSlotCount(), "商户卡片：低库存 1（D）");
        assertEquals(2, metrics.oosSlotCount(), "后台 KPI 必须与商户卡片同数");
        assertEquals(1, metrics.lowStockSlotCount(), "后台 KPI 必须与商户卡片同数");
        assertEquals(Integer.valueOf(31), card.fillRatePct(),
                "填充率 = Σmin(可售,容量)/Σ容量 = (0+0+4+1)/(4*4) 取整");
        assertEquals(31, metrics.fillRatePct(), "后台 KPI 填充率必须与商户卡片同源");
        assertNull(card.lastCheckAt(), "开门柜没有货道盘点");
    }

    // ══ 3. 商户库存列表（lowStockOnly）════════════════════════════════════
    @Test
    void merchantInventoryList_lowStockOnly_usesSameWarnSetAndSellableQty() {
        DeviceSkuInventoryMapper inventory = mock(DeviceSkuInventoryMapper.class);
        when(inventory.findByIdDeviceId(DEV)).thenReturn(inventoryRows());
        MerchantInventoryPortalService svc = merchantInventoryService(inventory, lotLedger());

        List<DeviceInventoryDto> rows = svc.listInventory(1L, DEV, true);

        assertEquals(WARN_SKUS, rows.stream().map(DeviceInventoryDto::skuId).sorted().toList(),
                "低库存筛选=可售≤阈值（含缺货），集合必须与 KPI/卡片一致");
        assertEquals(0, rows.stream().filter(r -> "SKU-B".equals(r.skuId())).findFirst()
                .orElseThrow().quantity(), "已建账 SKU 的数量源是批次可售，不是台账 quantity");
    }

    // ══ 4. 商户「缺货巡柜」════════════════════════════════════════════════
    @Test
    void merchantShortagePatrol_includesDoorCabinetWithSameCounts() {
        DeviceInfoMapper deviceRepository = mock(DeviceInfoMapper.class);
        when(deviceRepository.findAll()).thenReturn(List.of(doorDevice()));
        DeviceSkuInventoryMapper inventory = mock(DeviceSkuInventoryMapper.class);
        when(inventory.findByIdDeviceId(DEV)).thenReturn(inventoryRows());
        MerchantInventoryPortalService svc = merchantInventoryService(inventory, lotLedger(),
                deviceRepository);

        List<MerchantShortageSummaryDto> summary = svc.listShortageSummary(1L);

        assertEquals(1, summary.size(), "开门柜也要出现在缺货巡柜里（此前被整体排除）");
        assertEquals(3, summary.get(0).shortageSlots(), "待补商品数 = {A,B,D}");
        assertEquals(11, summary.get(0).shortageQty(), "缺口 = Σmax(容量−可售,0) = 4+4+3");
    }

    // ══ 5. 商户/运营工作台待办（条目数字=可售量）═════════════════════════════
    @Test
    void workbenchLowStockItems_renderSellableQty() {
        DeviceSkuInventoryMapper inventory = mock(DeviceSkuInventoryMapper.class);
        DeviceSlotService slotService = mock(DeviceSlotService.class);
        when(slotService.isDoorCabinet(DEV)).thenReturn(true);
        // SQL 层（lowStockPredicate + effectiveSellableQty）返回的就是可售量，第 6 条测试守这条 SQL
        when(inventory.findLowStockByDeviceIds(List.of(DEV)))
                .thenReturn(List.of(inv("SKU-A", 0, 4, 2), inv("SKU-B", 0, 4, 1), inv("SKU-D", 1, 4, 2)));
        SkuCatalogMapper catalog = mock(SkuCatalogMapper.class);
        when(catalog.findBySkuIds(any())).thenReturn(activeCatalog());
        MerchantWorkbenchQueryService svc = new MerchantWorkbenchQueryService(
                mock(PermissionService.class), mock(MerchantPortalGuard.class),
                mock(MerchantFeaturePackService.class), mock(DeviceInfoMapper.class),
                mock(CabinetOrderMapper.class), mock(OrderRevenueSplitMapper.class),
                mock(ShoppingSessionMapper.class), mock(DisputeTicketMapper.class), inventory,
                mock(PullOffTaskMapper.class), slotService, mock(ReplenishmentTaskMapper.class),
                catalog);

        List<OpsActionItemDto> items = new ArrayList<>();
        long count = svc.appendLowStockItems(items, Set.of(DEV));

        assertEquals(WARN_SKUS.size(), count, "待办条数必须与 KPI/卡片的告警 SKU 数一致");
        assertTrue(items.stream().noneMatch(i -> i.toString().contains("SKU-C")),
                "有货 SKU 不得进待办");
        assertTrue(items.stream().anyMatch(i -> i.toString().contains("当前 0 ")),
                "批次扣光的 SKU 显示的是可售 0，不是台账 quantity 3");
    }

    // ══ 6. 备货建议 / 备货单 ═══════════════════════════════════════════════
    @Test
    void replenishmentSuggest_triggerSetMatchesWarnSet() {
        DeviceSkuInventoryMapper deviceInventory = mock(DeviceSkuInventoryMapper.class);
        when(deviceInventory.findByIdDeviceId(DEV)).thenReturn(inventoryRows());
        DeviceSlotService slotService = mock(DeviceSlotService.class);
        when(slotService.isDoorCabinet(DEV)).thenReturn(true);
        when(slotService.suggestSlotsForDevice(DEV)).thenReturn(List.of());
        SalesVelocityService velocity = mock(SalesVelocityService.class);
        when(velocity.velocityBySku(DEV)).thenReturn(Map.of());
        InTransitService inTransit = mock(InTransitService.class);
        when(inTransit.qtyBySkuForDevice(DEV)).thenReturn(Map.of());
        WarehouseService svc = new WarehouseService(
                mock(WarehouseMapper.class), mock(WarehouseInventoryMapper.class),
                mock(WarehouseInboundMapper.class), mock(WarehouseInboundLineMapper.class),
                mock(WarehouseOutboundMapper.class), mock(WarehouseOutboundLineMapper.class),
                mock(WarehouseMovementMapper.class), deviceInventory, mock(ReplenishmentTaskMapper.class),
                mock(ReplenishmentRouteMapper.class), mock(SkuCatalogMapper.class), slotService, velocity,
                inTransit, lotLedger(), mock(DistributedLockService.class),
                mock(DisplaySnapshotHelper.class), mock(DeviceInfoMapper.class), null,
                mock(UserInfoMapper.class), mock(PermissionService.class),
                mock(MerchantScopeService.class), mock(AdminAuditService.class),
                mock(WarehouseManagerMapper.class), null);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "self", svc);

        List<ReplenishmentSuggestDto> suggests = svc.suggestForDevice(DEV);

        assertEquals(WARN_SKUS, suggests.stream().map(ReplenishmentSuggestDto::skuId).sorted().toList(),
                "备货建议触发集合=缺货/低库存集合（一张备货单就能覆盖首页说缺的）");
        Map<String, Integer> suggestBySku = suggests.stream()
                .collect(Collectors.toMap(ReplenishmentSuggestDto::skuId, ReplenishmentSuggestDto::suggestQty));
        assertEquals(4, suggestBySku.get("SKU-A"));
        assertEquals(4, suggestBySku.get("SKU-B"), "按可售 0 补到容量 4，不是按台账 3 只补 1");
        assertEquals(3, suggestBySku.get("SKU-D"));
    }

    // ══ 7. SQL 侧单源：低库存类 select 共用同一条谓词 ═══════════════════════
    @Test
    void lowStockSqlSelects_allShareSamePredicate() throws Exception {
        String xml = devicesXml();
        Matcher predicate = Pattern.compile("<sql id=\"lowStockPredicate\">([\\s\\S]*?)</sql>").matcher(xml);
        assertTrue(predicate.find(), "低库存判据必须是可复用 sql 片段，不许在各 select 里手抄");
        assertTrue(predicate.group(1).contains("effectiveSellableQty"),
                "判据数量源必须走批次可售（effectiveSellableQty），不能用台账 quantity 裸列");
        assertTrue(predicate.group(1).contains("i.low_threshold"), "判据上限是逐 SKU 低库存阈值");

        Matcher deployed = Pattern.compile("<sql id=\"deployedDeviceOnly\">([\\s\\S]*?)</sql>").matcher(xml);
        assertTrue(deployed.find(), "投放柜过滤片段必须存在（避免把下线/回收柜算进缺货指标）");
        assertTrue(deployed.group(1).contains("refid=\"lowStockPredicate\""),
                "deployedDeviceOnly 也必须共用同一条低库存判据");

        Matcher selects = Pattern
                .compile("<select\\s+id=\"((?:count|find)LowStock\\w*)\"[\\s\\S]*?</select>")
                .matcher(xml);
        int seen = 0;
        while (selects.find()) {
            seen++;
            String id = selects.group(1);
            String body = selects.group();
            // 直接 include 谓词，或经 deployedDeviceOnly（它内部 include 同一条谓词）——两条都算单源
            assertTrue(body.contains("refid=\"lowStockPredicate\"")
                            || body.contains("refid=\"deployedDeviceOnly\""),
                    "低库存 select 必须 include lowStockPredicate：" + id);
            assertFalse(body.contains("i.quantity <= i.low_threshold"),
                    "禁止手写裸列判据（quantity 只是台账快照，不是可售）：" + id);
            if (id.startsWith("findLowStock")) {
                assertTrue(body.contains("refid=\"inventorySelectEffective\""),
                        "findLowStock* 返回的 quantity 必须是可售量：" + id);
            }
        }
        assertTrue(seen >= 4, "解析器失效：只扫到 " + seen + " 条低库存 select（防恒真）");
    }

    // ── 服务装配 ────────────────────────────────────────────────────────────
    private static DeviceSlotService doorSlotService() {
        DeviceSlotMapper slots = mock(DeviceSlotMapper.class);
        DeviceSkuLotMapper lots = mock(DeviceSkuLotMapper.class);
        DeviceInfoMapper devices = mock(DeviceInfoMapper.class);
        DeviceSkuInventoryMapper inventory = mock(DeviceSkuInventoryMapper.class);
        SkuCatalogMapper catalog = mock(SkuCatalogMapper.class);
        ReplenishmentTaskMapper tasks = mock(ReplenishmentTaskMapper.class);
        ShoppingSessionMapper sessions = mock(ShoppingSessionMapper.class);
        JiangyiDeviceDirectory jiangyi = mock(JiangyiDeviceDirectory.class);
        when(devices.findById(DEV)).thenReturn(Optional.of(doorDevice()));
        when(jiangyi.isJiangyi(anyString())).thenReturn(true);
        when(slots.findByIdDeviceIdOrderByRowNoAscColNoAsc(DEV)).thenReturn(List.of());
        when(lots.sumBookQtyBySlot(DEV)).thenReturn(List.of());
        when(lots.findByDeviceId(DEV)).thenReturn(List.of());
        when(sessions.findFirstByDeviceIdOrderByCreatedAtDesc(DEV)).thenReturn(Optional.empty());
        when(tasks.findLastCompletedAtByDeviceId(anyString())).thenReturn(Optional.empty());
        when(inventory.findByIdDeviceId(DEV)).thenReturn(inventoryRows());
        when(catalog.selectList(any())).thenReturn(activeCatalog());
        DeviceSlotService svc = new DeviceSlotService(
                slots, lots, devices, inventory, catalog, null, tasks,
                mock(MerchantScopeService.class), null, null, lotLedger(), null,
                mock(DistributedLockService.class), sessions, mock(CabinetOrderLineMapper.class),
                mock(SkuSceneLimitService.class), null, jiangyi, null);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "self", svc);
        return svc;
    }

    private static MerchantInventoryPortalService merchantInventoryService(
            DeviceSkuInventoryMapper inventory, InventoryLotService lots) {
        return merchantInventoryService(inventory, lots, mock(DeviceInfoMapper.class));
    }

    private static MerchantInventoryPortalService merchantInventoryService(
            DeviceSkuInventoryMapper inventory, InventoryLotService lots,
            DeviceInfoMapper deviceRepository) {
        DeviceSlotService slotService = mock(DeviceSlotService.class);
        when(slotService.isDoorCabinet(anyString())).thenReturn(true);
        MerchantFeaturePackService packs = mock(MerchantFeaturePackService.class);
        // null = 不做功能包过滤（生产里两种取值都要能走通；这里只验口径）
        when(packs.allowedDeviceIdsForPack(any(), any())).thenReturn(null);
        return new MerchantInventoryPortalService(
                mock(PermissionService.class), mock(MerchantPortalGuard.class),
                packs, inventory, lots,
                mock(PullOffTaskMapper.class), slotService, mock(ReplenishmentTaskMapper.class),
                mock(ReplenishmentTaskLineMapper.class), mock(ReplenishmentRouteMapper.class),
                deviceRepository, mock(FileAttachmentService.class), mock(SkuCatalogMapper.class),
                mock(WarehouseOutboundMapper.class), mock(WarehouseOutboundLineMapper.class),
                mock(WarehouseInventoryMapper.class));
    }
}
