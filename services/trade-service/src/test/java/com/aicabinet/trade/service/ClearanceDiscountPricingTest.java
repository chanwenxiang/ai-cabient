package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceSkuLot;
import com.aicabinet.trade.domain.DeviceSkuPrice;
import com.aicabinet.trade.domain.DeviceSkuPriceId;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.*;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * F1 库存清仓折扣（切片 2）：策略归一化的**维度独立性**、滞销阈值（闭区间下界）、
 * 入库天数计算，以及与「临期价」（绝对价优先）「时段折扣」（取更深者）的叠加规则。
 *
 * <p>全部时间轴都用固定日期 {@link #TODAY} 注入，不依赖墙钟 —— 判据不能靠「今天恰好」
 * 去撞（见 {@code TimeWindowPricingTest} 同类约定）。
 */
@ExtendWith(MockitoExtension.class)
class ClearanceDiscountPricingTest {

    /** 固定「今天」：所有入库时间/到期日都由它推导，保证判据可复现。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 20);

    @Mock private DeviceSkuPriceMapper priceRepository;
    @Mock private DeviceSkuInventoryMapper inventoryRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private PermissionService permissionService;
    @Mock private MerchantPortalGuard merchantPortalGuard;
    @Mock private AdminAuditService auditService;
    @Mock private AdminAuditLogMapper auditLogRepository;
    @Mock private MerchantSelfServiceGate merchantSelfServiceGate;
    @Mock private MerchantFeaturePackService merchantFeaturePackService;
    @Mock private InventoryLotService inventoryLotService;
    @Mock private DistributedLockService distributedLockService;

    private MerchantSkuPricingService service() {
        MerchantSkuPricingService s = new MerchantSkuPricingService(
                priceRepository, inventoryRepository, skuCatalogRepository, deviceRepository,
                permissionService, merchantPortalGuard, auditService, auditLogRepository,
                merchantSelfServiceGate, merchantFeaturePackService, inventoryLotService,
                distributedLockService, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "self", s);
        return s;
    }

    // ── 维度独立性：一个维度配错不得连坐另一个（本切片最容易写错的一条） ────────

    @Test
    void policy_timeWindowInvalid_keepsClearanceWorking() {
        // 时段是「空窗口」（start == end ⇒ 该维度不生效），但清仓配置合法。
        PricingPromoPolicy p = PricingPromoPolicy.of(true, 0, 0, 10, true, 30, 30);
        for (int h = 0; h <= 23; h++) {
            assertFalse(p.activeAt(h), "空窗口不应在任何小时生效，h=" + h);
        }
        assertTrue(p.clearanceAppliesAt(30), "时段配错不得连坐清仓");
        assertEquals(30, p.clearanceDiscountAt(40));
    }

    @Test
    void policy_clearanceInvalid_keepsTimeWindowWorking() {
        // 清仓天数阈值配成 0（不生效），但时段配置合法。
        PricingPromoPolicy p = PricingPromoPolicy.of(true, 12, 14, 10, true, 0, 30);
        assertTrue(p.activeAt(13), "清仓配错不得连坐时段");
        assertEquals(10, p.discountPercentAt(13));
        for (int d = 0; d <= 500; d++) {
            assertFalse(p.clearanceAppliesAt(d), "阈值非法时任何天数都不应触发清仓，d=" + d);
        }
    }

    @Test
    void policy_bothDimensionsValid_bothActive() {
        PricingPromoPolicy p = PricingPromoPolicy.of(true, 12, 14, 10, true, 30, 25);
        assertEquals(10, p.discountPercentAt(13));
        assertEquals(0, p.discountPercentAt(15));
        assertEquals(25, p.clearanceDiscountAt(30));
    }

    @Test
    void policy_bothInvalid_returnsDisabledSentinel() {
        assertSame(PricingPromoPolicy.disabled(),
                PricingPromoPolicy.of(false, 0, 0, 0, false, 0, 0));
        assertSame(PricingPromoPolicy.disabled(),
                PricingPromoPolicy.of(true, 5, 5, 100, true, -1, 100));
    }

    // ── 越界归一化 + 阈值是闭区间下界 ────────────────────────────────────────

    @Test
    void policy_clearancePercentInvalid_isInactive() {
        assertFalse(PricingPromoPolicy.of(true, 0, 0, 0, true, 30, 0).clearanceAppliesAt(999));
        assertFalse(PricingPromoPolicy.of(true, 0, 0, 0, true, 30, 100).clearanceAppliesAt(999));
        assertFalse(PricingPromoPolicy.of(true, 0, 0, 0, true, 30, -5).clearanceAppliesAt(999));
    }

    @Test
    void policy_clearanceThreshold_isClosedLowerBound() {
        PricingPromoPolicy p = PricingPromoPolicy.of(true, 0, 0, 0, true, 30, 20);
        assertFalse(p.clearanceAppliesAt(29), "差一天不算滞销");
        assertTrue(p.clearanceAppliesAt(30), "满阈值当天即算滞销（闭下界）");
        assertEquals(20, p.clearanceDiscountAt(31));
        assertEquals(0, p.clearanceDiscountAt(29));
    }

    // ── 入库天数：同日/未来一律 0（宁可「不算滞销」，不可把新货当滞销清仓） ────

    @Test
    void stockAgeDays_sameDayOrFutureOrNull_isZero() {
        ZoneId zone = ZoneId.systemDefault();
        Instant stockedToday = TODAY.atStartOfDay(zone).toInstant();
        assertEquals(0, InventoryLotService.stockAgeDays(stockedToday, TODAY));
        assertEquals(0, InventoryLotService.stockAgeDays(
                TODAY.plusDays(3).atStartOfDay(zone).toInstant(), TODAY), "未来时间（时钟回拨）不得算出负数");
        assertEquals(0, InventoryLotService.stockAgeDays(null, TODAY));
        assertEquals(0, InventoryLotService.stockAgeDays(stockedToday, null));
    }

    @Test
    void stockAgeDays_countsWholeDays() {
        ZoneId zone = ZoneId.systemDefault();
        assertEquals(30, InventoryLotService.stockAgeDays(
                TODAY.minusDays(30).atStartOfDay(zone).toInstant(), TODAY));
    }

    // ── 定价链：清仓生效 / 不生效 ───────────────────────────────────────────

    @Test
    void resolve_whenClearanceActive_appliesDiscount() {
        SkuCatalog sku = sku("SKU-1", 500);
        stubNoOverride("CAB-1", "SKU-1");
        stubFarFutureLot("CAB-1", "SKU-1");
        when(inventoryLotService.oldestSellableLotCreatedAt("CAB-1", "SKU-1"))
                .thenReturn(Optional.of(daysBeforeToday(40)));

        PricingPromoPolicy p = PricingPromoPolicy.of(false, 0, 0, 0, true, 30, 30);
        // 500 × 70% = 350
        assertEquals(350, service().resolveUnitPriceCents("CAB-1", sku, p, 13, TODAY));
    }

    @Test
    void resolve_whenStockAgeBelowThreshold_noDiscount() {
        SkuCatalog sku = sku("SKU-1", 500);
        stubNoOverride("CAB-1", "SKU-1");
        stubFarFutureLot("CAB-1", "SKU-1");
        when(inventoryLotService.oldestSellableLotCreatedAt("CAB-1", "SKU-1"))
                .thenReturn(Optional.of(daysBeforeToday(20)));

        PricingPromoPolicy p = PricingPromoPolicy.of(false, 0, 0, 0, true, 30, 30);
        assertEquals(500, service().resolveUnitPriceCents("CAB-1", sku, p, 13, TODAY));
    }

    @Test
    void resolve_whenNoSellableLot_noClearance() {
        SkuCatalog sku = sku("SKU-1", 500);
        stubNoOverride("CAB-1", "SKU-1");
        stubFarFutureLot("CAB-1", "SKU-1");
        when(inventoryLotService.oldestSellableLotCreatedAt("CAB-1", "SKU-1"))
                .thenReturn(Optional.empty());

        PricingPromoPolicy p = PricingPromoPolicy.of(false, 0, 0, 0, true, 30, 30);
        // 无可售批次 ⇒ 无从判定滞销 ⇒ 原价（fail-closed）
        assertEquals(500, service().resolveUnitPriceCents("CAB-1", sku, p, 13, TODAY));
    }

    @Test
    void resolve_clearanceAndWindow_takesDeeperAndNeverStacks() {
        // 时段 10% vs 清仓 30% ⇒ 取 30%（折上折会是 1-0.9×0.7=37%，两种都不是）
        SkuCatalog skuA = sku("SKU-A", 500);
        stubNoOverride("CAB-1", "SKU-A");
        stubFarFutureLot("CAB-1", "SKU-A");
        when(inventoryLotService.oldestSellableLotCreatedAt("CAB-1", "SKU-A"))
                .thenReturn(Optional.of(daysBeforeToday(40)));
        PricingPromoPolicy deeperClearance = PricingPromoPolicy.of(true, 12, 14, 10, true, 30, 30);
        assertEquals(350, service().resolveUnitPriceCents("CAB-1", skuA, deeperClearance, 13, TODAY));

        // 时段 50% vs 清仓 30% ⇒ 取 50%
        SkuCatalog skuB = sku("SKU-B", 500);
        stubNoOverride("CAB-1", "SKU-B");
        stubFarFutureLot("CAB-1", "SKU-B");
        when(inventoryLotService.oldestSellableLotCreatedAt("CAB-1", "SKU-B"))
                .thenReturn(Optional.of(daysBeforeToday(40)));
        PricingPromoPolicy deeperWindow = PricingPromoPolicy.of(true, 12, 14, 50, true, 30, 30);
        assertEquals(250, service().resolveUnitPriceCents("CAB-1", skuB, deeperWindow, 13, TODAY));
    }

    @Test
    void resolve_whenNearExpiry_doesNotStackClearance() {
        SkuCatalog sku = sku("SKU-1", 500);
        sku.setNearExpiryPriceCents(299);
        DeviceSkuLot lot = new DeviceSkuLot();
        lot.setLotId("LOT-1");
        lot.setExpiryDate(TODAY.plusDays(3)); // 落在默认 7 天临期窗口内
        when(priceRepository.findByDeviceIdAndSkuId("CAB-1", "SKU-1")).thenReturn(Optional.empty());
        when(inventoryLotService.peekFefoPrimarySellableLot("CAB-1", "SKU-1")).thenReturn(Optional.of(lot));

        PricingPromoPolicy p = PricingPromoPolicy.of(false, 0, 0, 0, true, 30, 30);
        // 命中临期 ⇒ 按人工设定的绝对价 299 成交；清仓不叠加，
        // 且**根本不该去查入库时间**（未 stub，若被调用 strict stubs 会立即报错）
        assertEquals(299, service().resolveUnitPriceCents("CAB-1", sku, p, 13, TODAY));
    }

    @Test
    void resolve_clearanceAppliesToDeviceOverridePrice() {
        SkuCatalog sku = sku("SKU-1", 500);
        DeviceSkuPriceId id = new DeviceSkuPriceId("CAB-1", "SKU-1");
        DeviceSkuPrice override = new DeviceSkuPrice();
        override.setId(id);
        override.setPriceCents(800);
        when(priceRepository.findByDeviceIdAndSkuId("CAB-1", "SKU-1")).thenReturn(Optional.of(override));
        stubFarFutureLot("CAB-1", "SKU-1");
        when(inventoryLotService.oldestSellableLotCreatedAt("CAB-1", "SKU-1"))
                .thenReturn(Optional.of(daysBeforeToday(40)));

        PricingPromoPolicy p = PricingPromoPolicy.of(false, 0, 0, 0, true, 30, 30);
        // 折扣作用于「生效价」（设备覆盖价 800），不是目录价 500
        assertEquals(560, service().resolveUnitPriceCents("CAB-1", sku, p, 13, TODAY));
    }

    @Test
    void resolve_clearanceNeverReachesZero() {
        SkuCatalog sku = sku("SKU-1", 1);
        stubNoOverride("CAB-1", "SKU-1");
        stubFarFutureLot("CAB-1", "SKU-1");
        when(inventoryLotService.oldestSellableLotCreatedAt("CAB-1", "SKU-1"))
                .thenReturn(Optional.of(daysBeforeToday(40)));

        PricingPromoPolicy p = PricingPromoPolicy.of(false, 0, 0, 0, true, 30, 99);
        // 1 分 × 1% 向下取整 = 0 ⇒ 保底 1 分，不出现 0 元订单行
        assertEquals(1, service().resolveUnitPriceCents("CAB-1", sku, p, 13, TODAY));
    }

    @Test
    void resolve_whenClearanceDisabled_isByteIdenticalToBefore() {
        SkuCatalog sku = sku("SKU-1", 500);
        stubNoOverride("CAB-1", "SKU-1");
        stubFarFutureLot("CAB-1", "SKU-1");

        // 清仓关闭（只配时段）⇒ 结果与「只有时段折扣」时一致；
        // 且**不许去查入库时间**（未 stub oldestSellableLotCreatedAt）
        PricingPromoPolicy p = PricingPromoPolicy.of(true, 12, 14, 10);
        assertEquals(450, service().resolveUnitPriceCents("CAB-1", sku, p, 13, TODAY));
    }

    @Test
    void resolve_nullSku_staysZero() {
        assertEquals(0, service().resolveUnitPriceCents("CAB-1", null,
                PricingPromoPolicy.of(false, 0, 0, 0, true, 30, 30), 13, TODAY));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void stubNoOverride(String deviceId, String skuId) {
        when(priceRepository.findByDeviceIdAndSkuId(deviceId, skuId)).thenReturn(Optional.empty());
    }

    private void stubFarFutureLot(String deviceId, String skuId) {
        DeviceSkuLot lot = new DeviceSkuLot();
        lot.setLotId("LOT-1");
        // 距到期 365 天 ⇒ 绝不临期（nearExpiryDays 默认 7），把临期维度排除在外
        lot.setExpiryDate(TODAY.plusDays(365));
        when(inventoryLotService.peekFefoPrimarySellableLot(deviceId, skuId)).thenReturn(Optional.of(lot));
    }

    private static Instant daysBeforeToday(int days) {
        return TODAY.minusDays(days).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private static SkuCatalog sku(String skuId, int priceCents) {
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId(skuId);
        sku.setSkuName(skuId);
        sku.setPriceCents(priceCents);
        sku.setStatus("ACTIVE");
        return sku;
    }
}
