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

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * F1 时段折扣：策略归一化（fail-closed）、窗口判定（含跨零点）、折扣取整与保底、
 * 以及与临期价的**不叠加**关系。
 */
@ExtendWith(MockitoExtension.class)
class TimeWindowPricingTest {

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

    // ── 策略归一化：任何越界都收敛成「不生效」，绝不猜近似值 ─────────────────

    @Test
    void policy_whenDisabled_isInactive() {
        PricingPromoPolicy p = PricingPromoPolicy.of(false, 12, 14, 10);
        assertFalse(p.activeAt(12));
        assertFalse(p.activeAt(13));
        assertEquals(0, p.discountPercentAt(13));
    }

    @Test
    void policy_whenHoursOutOfRange_isInactive() {
        assertFalse(PricingPromoPolicy.of(true, 25, 3, 10).activeAt(1));
        assertFalse(PricingPromoPolicy.of(true, -1, 3, 10).activeAt(1));
        assertFalse(PricingPromoPolicy.of(true, 0, 99, 10).activeAt(1));
    }

    @Test
    void policy_whenPercentInvalid_isInactive() {
        // 0 = 没配；100 = 「白送」，同样视为非法 ⇒ 宁可原价也不送。
        assertFalse(PricingPromoPolicy.of(true, 12, 14, 0).activeAt(12));
        assertFalse(PricingPromoPolicy.of(true, 12, 14, 100).activeAt(12));
        assertFalse(PricingPromoPolicy.of(true, 12, 14, -5).activeAt(12));
    }

    @Test
    void policy_whenStartEqualsEnd_isEmptyWindowAndInactive() {
        PricingPromoPolicy p = PricingPromoPolicy.of(true, 0, 0, 10);
        for (int h = 0; h <= 23; h++) {
            assertFalse(p.activeAt(h), "空窗口不应在任何小时生效，h=" + h);
        }
    }

    // ── 窗口判定：起点含、终点不含；跨零点按「或」 ──────────────────────────

    @Test
    void policy_normalWindow_includesStartExcludesEnd() {
        PricingPromoPolicy p = PricingPromoPolicy.of(true, 12, 14, 10);
        assertFalse(p.activeAt(11));
        assertTrue(p.activeAt(12), "起点小时应生效");
        assertTrue(p.activeAt(13));
        assertFalse(p.activeAt(14), "终点小时不应生效（左闭右开）");
        assertFalse(p.activeAt(15));
    }

    @Test
    void policy_overnightWindow_wrapsMidnight() {
        PricingPromoPolicy p = PricingPromoPolicy.of(true, 22, 6, 10);
        assertTrue(p.activeAt(22));
        assertTrue(p.activeAt(23));
        assertTrue(p.activeAt(0));
        assertTrue(p.activeAt(5));
        assertFalse(p.activeAt(6), "终点小时不应生效（左闭右开）");
        assertFalse(p.activeAt(12));
    }

    // ── 折扣取整与保底 ──────────────────────────────────────────────────────

    @Test
    void applyDiscount_whenPercentInvalid_returnsOriginalPrice() {
        assertEquals(500, MerchantSkuPricingService.applyTimeWindowDiscount(500, 0));
        assertEquals(500, MerchantSkuPricingService.applyTimeWindowDiscount(500, 100));
        assertEquals(500, MerchantSkuPricingService.applyTimeWindowDiscount(500, -1));
        assertEquals(0, MerchantSkuPricingService.applyTimeWindowDiscount(0, 10));
    }

    @Test
    void applyDiscount_roundsDown() {
        // 500 × 90% = 450；499 × 90% = 449.1 ⇒ 向下取整 449（对商户有利，不会多折）
        assertEquals(450, MerchantSkuPricingService.applyTimeWindowDiscount(500, 10));
        assertEquals(449, MerchantSkuPricingService.applyTimeWindowDiscount(499, 10));
    }

    @Test
    void applyDiscount_neverReachesZero() {
        // 1 分 × 99% 向下取整为 0 ⇒ 保底 1 分，避免出现 0 元订单行
        assertEquals(1, MerchantSkuPricingService.applyTimeWindowDiscount(1, 99));
        assertEquals(1, MerchantSkuPricingService.applyTimeWindowDiscount(2, 99));
    }

    // ── 定价链：开关关 ⇒ 与接入前一致；开 ⇒ 只在窗口内打折 ──────────────────

    @Test
    void resolve_whenPolicyInactive_returnsCatalogPriceUnchanged() {
        SkuCatalog sku = sku("SKU-1", 500);
        MerchantSkuPricingService svc = service();
        // deviceId == null 路径不查库；策略关闭 ⇒ 原价
        assertEquals(500, svc.resolveUnitPriceCents(null, sku, PricingPromoPolicy.disabled(), 13));
    }

    @Test
    void resolve_whenWithinWindow_appliesDiscount() {
        SkuCatalog sku = sku("SKU-1", 500);
        MerchantSkuPricingService svc = service();
        PricingPromoPolicy p = PricingPromoPolicy.of(true, 12, 14, 10);
        assertEquals(450, svc.resolveUnitPriceCents(null, sku, p, 13));
        // 窗口外原价
        assertEquals(500, svc.resolveUnitPriceCents(null, sku, p, 15));
    }

    @Test
    void resolve_whenNullPolicy_doesNotDiscount() {
        SkuCatalog sku = sku("SKU-1", 500);
        // 调用方忘了传策略（mock 返回 null）时按原价 —— 宁可不少收，不可乱打折
        assertEquals(500, service().resolveUnitPriceCents(null, sku, null, 13));
    }

    @Test
    void resolve_discountsDeviceOverridePriceToo() {
        SkuCatalog sku = sku("SKU-1", 500);
        DeviceSkuPriceId id = new DeviceSkuPriceId("CAB-1", "SKU-1");
        DeviceSkuPrice override = new DeviceSkuPrice();
        override.setId(id);
        override.setPriceCents(800);
        when(priceRepository.findByDeviceIdAndSkuId("CAB-1", "SKU-1")).thenReturn(Optional.of(override));
        when(inventoryLotService.peekFefoPrimarySellableLot("CAB-1", "SKU-1")).thenReturn(Optional.empty());

        PricingPromoPolicy p = PricingPromoPolicy.of(true, 12, 14, 10);
        // 覆盖价 800 也要打折（折扣作用于「生效价」，不是目录价）
        assertEquals(720, service().resolveUnitPriceCents("CAB-1", sku, p, 13));
    }

    @Test
    void resolve_whenNearExpiry_doesNotStackTimeDiscount() {
        SkuCatalog sku = sku("SKU-1", 500);
        sku.setNearExpiryPriceCents(299);
        DeviceSkuLot lot = new DeviceSkuLot();
        lot.setLotId("LOT-1");
        lot.setExpiryDate(LocalDate.now().plusDays(3));

        when(priceRepository.findByDeviceIdAndSkuId("CAB-1", "SKU-1")).thenReturn(Optional.empty());
        when(inventoryLotService.peekFefoPrimarySellableLot("CAB-1", "SKU-1")).thenReturn(Optional.of(lot));

        PricingPromoPolicy p = PricingPromoPolicy.of(true, 12, 14, 10);
        // 临期价 299 是人工设定的绝对促销价 ⇒ 不再叠加时段折扣（否则商户算不出最终售价）
        assertEquals(299, service().resolveUnitPriceCents("CAB-1", sku, p, 13));
    }

    @Test
    void resolve_whenLotExistsButNotNearExpiry_appliesDiscountOnCatalogPrice() {
        SkuCatalog sku = sku("SKU-1", 500);
        sku.setNearExpiryPriceCents(299);
        DeviceSkuLot lot = new DeviceSkuLot();
        lot.setLotId("LOT-1");
        // 距到期 30 天 > 临期窗口 7 天 ⇒ 不算临期 ⇒ 走目录价 + 折扣
        lot.setExpiryDate(LocalDate.now().plusDays(30));

        when(priceRepository.findByDeviceIdAndSkuId("CAB-1", "SKU-1")).thenReturn(Optional.empty());
        when(inventoryLotService.peekFefoPrimarySellableLot("CAB-1", "SKU-1")).thenReturn(Optional.of(lot));

        PricingPromoPolicy p = PricingPromoPolicy.of(true, 12, 14, 10);
        assertEquals(450, service().resolveUnitPriceCents("CAB-1", sku, p, 13));
    }

    @Test
    void resolve_nullSku_staysZero() {
        assertEquals(0, service().resolveUnitPriceCents("CAB-1", null,
                PricingPromoPolicy.of(true, 12, 14, 10), 13));
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
