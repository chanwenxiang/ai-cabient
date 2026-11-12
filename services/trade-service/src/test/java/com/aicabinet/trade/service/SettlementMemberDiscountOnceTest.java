package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceSlotMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.recognition.RecognizedItem;
import com.aicabinet.trade.service.view.OrderViewAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MA-029 方案 A（2026-11-02 拍板）——会员折扣只生效一次的口径钉测。
 *
 * <p>权威口径 ①：会员价直接算进 {@code unit_price_cents}，所以行金额/合计里已经含会员折扣；
 * {@code member_discount_cents} 自本批起是「Σ(目录价 − 会员价) × 数量」的**留档列**，
 * 永远不作为订单级减项。应付恒等式：
 * {@code gross − member − activity − coupon = total}。
 *
 * <p>反例（②）在每一条断言里都被显式排除：少收一分为资损，多收一分为错账。
 */
@ExtendWith(MockitoExtension.class)
class SettlementMemberDiscountOnceTest {

    @Mock CabinetOrderMapper orderRepository;
    @Mock CabinetOrderLineMapper orderLineRepository;
    @Mock SkuCatalogMapper skuCatalogRepository;
    @Mock DeviceSlotMapper slotRepository;
    @Mock MerchantSkuPricingService skuPricingService;
    @Mock MemberService memberService;
    @Mock CouponService couponService;
    @Mock RevenueSplitService revenueSplitService;
    @Mock OrderPaymentService orderPaymentService;
    @Mock PromotionPricingService promotionPricingService;

    SettlementOrderSupport support;

    @BeforeEach
    void setUp() {
        support = new SettlementOrderSupport(
                skuCatalogRepository, orderRepository, orderLineRepository, slotRepository,
                skuPricingService, memberService, couponService, null,
                revenueSplitService, orderPaymentService, null, new OrderViewAssembler(),
                promotionPricingService);
    }

    private CabinetOrder orderWithDevice(String deviceId) {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-M1");
        order.setSessionId("S-M1");
        order.setUserId(10001L);
        order.setDeviceId(deviceId);
        order.setStatus("PENDING");
        return order;
    }

    private void stubSku(String skuId, String deviceId, int listUnitPrice, int memberUnitPrice) {
        var sku = new com.aicabinet.trade.domain.SkuCatalog();
        sku.setSkuId(skuId);
        sku.setSkuName(skuId);
        when(skuCatalogRepository.findById(skuId)).thenReturn(Optional.of(sku));
        when(skuPricingService.resolveUnitPriceCents(deviceId, sku)).thenReturn(listUnitPrice);
        when(memberService.applyMemberPriceDiscount(10001L, listUnitPrice)).thenReturn(memberUnitPrice);
        when(slotRepository.findByIdDeviceId(deviceId)).thenReturn(List.of());
    }

    @Nested
    class ApplyItemsToOrder {

        /** ①入账：会员档 = Σ(目录价 − 会员价) × 数量，且应付里只在单价减过一次。 */
        @Test
        void memberOnly_booksArchiveAndPaysOnce() {
            CabinetOrder order = orderWithDevice("CAB-9");
            stubSku("SKU-A", "CAB-9", 350, 343);
            when(promotionPricingService.resolveBestForOrder(
                    anyLong(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
                    .thenReturn(Optional.empty());

            support.applyItemsToOrder(order, List.of(new RecognizedItem("SKU-A", 1, 1f)));

            CabinetOrderLine line = order.getLines().get(0);
            assertEquals(343, line.getUnitPriceCents());
            assertEquals(343, line.getLineAmountCents());
            assertEquals(350, order.getGrossAmountCents());
            assertEquals(7, order.getMemberDiscountCents());
            assertEquals(0, order.getActivityDiscountCents());
            assertEquals(343, order.getTotalAmountCents());
            assertEquals(350 - 7 - 0 - 0, order.getTotalAmountCents());
        }

        /**
         * 反例钉测：会员 + 活动同时命中 ⇒ **不叠加、取优**（2026-11-12 拍板）。
         *
         * <p>活动价以**目录价** 400 为基价算得 342，低于会员价 392 ⇒ 取活动价 342；
         * 活动留档 = (会员价 − 活动价) × 3 = 150，会员留档仍是 (目录价 − 会员价) × 3 = 24，
         * 恒等式 1200 − 24 − 150 = 1026 成立。若把会员价 392 当活动基价（旧实现），
         * 折扣型活动会在会员价上再打一次折，等于双重优惠。</p>
         */
        @Test
        void memberAndActivityTogether_bestOfTwoNotStacked() {
            CabinetOrder order = orderWithDevice("CAB-9");
            stubSku("SKU-A", "CAB-9", 400, 392);
            when(promotionPricingService.resolveBestForOrder(
                    eq(10001L), eq("CAB-9"), anyString(), eq("SKU-A"), eq(400), eq(392)))
                    .thenReturn(Optional.of(new PromotionPricingService.Applied(7L, 342, 50)));

            support.applyItemsToOrder(order, List.of(new RecognizedItem("SKU-A", 3, 1f)));

            CabinetOrderLine line = order.getLines().get(0);
            assertEquals(342, line.getUnitPriceCents());
            assertEquals(1026, line.getLineAmountCents());
            assertEquals(1200, order.getGrossAmountCents());
            assertEquals(24, order.getMemberDiscountCents());
            assertEquals(150, order.getActivityDiscountCents());
            // ②口径会把留档再减一次 ⇒ 1002（少收 24 分＝资损）
            assertEquals(1026, order.getTotalAmountCents());
            assertEquals(1200 - 24 - 150 - 0, order.getTotalAmountCents());
        }

        /** 非会员（会员价 = 目录价）⇒ 会员档恒 0，行为与本批之前完全一致。 */
        @Test
        void nonMember_booksZeroArchive() {
            CabinetOrder order = orderWithDevice("CAB-9");
            stubSku("SKU-A", "CAB-9", 400, 400);
            when(promotionPricingService.resolveBestForOrder(
                    anyLong(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
                    .thenReturn(Optional.empty());

            support.applyItemsToOrder(order, List.of(new RecognizedItem("SKU-A", 2, 1f)));

            assertEquals(0, order.getMemberDiscountCents());
            assertEquals(800, order.getGrossAmountCents());
            assertEquals(800, order.getTotalAmountCents());
        }
    }

    @Nested
    class PayableRecalculation {

        /** 争议改单：应付 = 行合计 − 券，会员档只留档。 */
        @Test
        void recalculateAfterLineChange_subtractsOnlyCoupon() {
            CabinetOrder order = orderWithDevice("CAB-9");
            order.setCouponId(7L);
            order.setMemberDiscountCents(24);
            order.setGrossAmountCents(1200);
            order.setLines(new ArrayList<>(List.of(line("SKU-A", 3, 392))));
            when(couponService.discountForOrderCoupon(7L, 1176)).thenReturn(100);

            support.recalculatePayableAfterLineChange(order);

            assertEquals(100, order.getCouponDiscountCents());
            assertEquals(24, order.getMemberDiscountCents());
            // ②口径为 1176 − 100 − 24 = 1052
            assertEquals(1076, order.getTotalAmountCents());
        }

        /** 部分退款：会员档按比例缩减只为留档，应付/退款额按「剩余行合计 − 券」。 */
        @Test
        void proportionalRefundPayable_scalesArchiveWithoutTouchingPayable() {
            CabinetOrder order = orderWithDevice("CAB-9");
            order.setCouponId(7L);
            order.setCouponDiscountCents(100);
            order.setMemberDiscountCents(24);
            order.setTotalAmountCents(1076);
            when(couponService.settleAfterPartialRefund(order, 1176, 392)).thenReturn(100);

            support.applyProportionalRefundPayable(order, 1176, 392);

            assertEquals(8, order.getMemberDiscountCents());
            assertEquals(100, order.getCouponDiscountCents());
            // ②口径为 392 − 100 − 8 = 284 ⇒ 会多退 8 分
            assertEquals(292, order.getTotalAmountCents());
            assertEquals(784, 1076 - 292);
        }

        /** 无券单：会员档再大也不动应付（防止把留档当减项的回归）。 */
        @Test
        void proportionalRefundPayable_withoutCoupon_keepsFullLineDelta() {
            CabinetOrder order = orderWithDevice("CAB-9");
            order.setMemberDiscountCents(24);
            order.setTotalAmountCents(1176);

            support.applyProportionalRefundPayable(order, 1176, 784);

            assertEquals(16, order.getMemberDiscountCents());
            assertEquals(784, order.getTotalAmountCents());
        }
    }

    /**
     * P2-4：争议改单沿用原单活动决策（成交即定价，对齐 Shopify 草稿单 price lock 口径）——
     * 原单有活动的行不因「此刻活动已撤」丢价；原单没活动的行也不因「此刻新配活动」变成活动+券双折。
     */
    @Nested
    class DisputeRepriceKeepsOriginalActivity {

        @Test
        @DisplayName("原单有活动的行：不读当前活动，按每件减额沿用")
        void existingLine_keepsOriginalActivityPrice() {
            CabinetOrder order = orderWithDevice("CAB-9");
            CabinetOrderLine original = line("SKU-A", 2, 250);
            original.setActivityId(9L);
            original.setActivityDiscountCents(100); // 每件减 50
            stubSku("SKU-A", "CAB-9", 300, 300);

            support.applyItemsToOrder(order, List.of(new RecognizedItem("SKU-A", 3, 1f)),
                    SettlementOrderSupport.preservedActivityFromLines(List.of(original)));

            CabinetOrderLine repriced = order.getLines().get(0);
            assertEquals(250, repriced.getUnitPriceCents(), "沿用原单每件减 50");
            assertEquals(9L, repriced.getActivityId(), "活动归属仍是原活动");
            assertEquals(150, order.getActivityDiscountCents());
            assertEquals(900, order.getGrossAmountCents());
            assertEquals(900 - 0 - 150 - 0, order.getTotalAmountCents());
            verifyNoInteractions(promotionPricingService);
        }

        @Test
        @DisplayName("原单没活动的行：新配活动也不套上去（避免活动+券双折）")
        void existingLine_withoutActivity_ignoresNewActivity() {
            CabinetOrder order = orderWithDevice("CAB-9");
            CabinetOrderLine original = line("SKU-A", 2, 300);
            stubSku("SKU-A", "CAB-9", 300, 300);
            // lenient：改单链根本不该再问当前活动；留着桩是为了「即使此刻配了活动也不生效」
            org.mockito.Mockito.lenient().when(promotionPricingService.resolveBestForOrder(
                    anyLong(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
                    .thenReturn(Optional.of(new PromotionPricingService.Applied(12L, 200, 100)));

            support.applyItemsToOrder(order, List.of(new RecognizedItem("SKU-A", 3, 1f)),
                    SettlementOrderSupport.preservedActivityFromLines(List.of(original)));

            assertEquals(0, order.getActivityDiscountCents());
            assertEquals(300, order.getLines().get(0).getUnitPriceCents());
            assertEquals(900, order.getTotalAmountCents());
            org.mockito.Mockito.verify(promotionPricingService, org.mockito.Mockito.never())
                    .resolveBestForOrder(anyLong(), anyString(), anyString(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("改单新增的 SKU：按当前规则定价（原单没有决策可沿用）")
        void addedSku_usesCurrentActivity() {
            CabinetOrder order = orderWithDevice("CAB-9");
            CabinetOrderLine original = line("SKU-A", 1, 300);
            stubSku("SKU-NEW", "CAB-9", 500, 500);
            when(promotionPricingService.resolveBestForOrder(
                    anyLong(), anyString(), anyString(), anyString(), anyInt(), anyInt()))
                    .thenReturn(Optional.of(new PromotionPricingService.Applied(21L, 450, 50)));

            support.applyItemsToOrder(order, List.of(new RecognizedItem("SKU-NEW", 2, 1f)),
                    SettlementOrderSupport.preservedActivityFromLines(List.of(original)));

            CabinetOrderLine added = order.getLines().get(0);
            assertEquals(450, added.getUnitPriceCents());
            assertEquals(21L, added.getActivityId());
            assertEquals(100, order.getActivityDiscountCents());
            assertEquals(900, order.getTotalAmountCents());
        }
    }

    private static CabinetOrderLine line(String skuId, int qty, int unitCents) {
        CabinetOrderLine line = new CabinetOrderLine();
        line.setSkuId(skuId);
        line.setSkuName(skuId);
        line.setQuantity(qty);
        line.setUnitPriceCents(unitCents);
        line.setLineAmountCents(unitCents * qty);
        return line;
    }
}
