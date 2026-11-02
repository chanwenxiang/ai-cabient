package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.service.view.OrderViewAssembler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SettlementOrderFinalizeAmountTest {

    @Test
    @DisplayName("券单转 PENDING：还原到折前应付（total + coupon）")
    void clearCoupon_restoresPreCouponAmount() {
        CabinetOrder order = new CabinetOrder();
        order.setTotalAmountCents(900);
        order.setCouponDiscountCents(100);
        order.setOriginalAmountCents(1000);

        SettlementOrderFinalizeService.clearCouponSelection(order);

        assertEquals(1000, order.getTotalAmountCents());
        assertEquals(1000, order.getOriginalAmountCents());
        assertEquals(0, order.getCouponDiscountCents());
        assertNull(order.getCouponId());
    }

    @Test
    @DisplayName("活动单转 PENDING：保留活动价，不得还原到活动前")
    void clearCoupon_activityOrder_keepsActivityPrice() {
        CabinetOrder order = new CabinetOrder();
        order.setTotalAmountCents(1026);
        order.setCouponDiscountCents(0);
        order.setActivityDiscountCents(150);
        // applyItemsToOrder 写入的是活动前合计（会员价合计）
        order.setOriginalAmountCents(1176);

        SettlementOrderFinalizeService.clearCouponSelection(order);

        assertEquals(1026, order.getTotalAmountCents(), "PENDING 应付必须仍是活动价");
        assertEquals(1026, order.getOriginalAmountCents());
        assertEquals(0, order.getCouponDiscountCents());
        assertNull(order.getCouponId());
    }

    @Test
    @DisplayName("争议改单重算：活动价存在时不叠加券（与结算主链同口径）")
    void recalculate_activityOrder_skipsCoupon() {
        CouponService couponService = mock(CouponService.class);
        SettlementOrderSupport support = new SettlementOrderSupport(
                null, null, null, null, null, null, couponService, null,
                null, null, null, new OrderViewAssembler(), null);

        CabinetOrder order = new CabinetOrder();
        order.setActivityDiscountCents(150);
        order.setCouponId(42L);
        order.setCouponDiscountCents(100);
        CabinetOrderLine line = new CabinetOrderLine();
        line.setSkuId("SKU-A");
        line.setQuantity(3);
        line.setLineAmountCents(1026);
        order.setLines(new ArrayList<>(List.of(line)));

        support.recalculatePayableAfterLineChange(order);

        assertEquals(0, order.getCouponDiscountCents());
        assertEquals(1026, order.getTotalAmountCents());
        verifyNoInteractions(couponService);
    }

    @Test
    @DisplayName("争议重算：高会员折扣（>50%）不再把留档夹小，恒等式保持")
    void recalculate_highMemberDiscount_keepsArchive() {
        SettlementOrderSupport support = new SettlementOrderSupport(
                null, null, null, null, null, null, mock(CouponService.class), null,
                null, null, null, new OrderViewAssembler(), null);
        CabinetOrder order = new CabinetOrder();
        order.setGrossAmountCents(700);
        order.setMemberDiscountCents(525); // 75% 折扣
        order.setActivityDiscountCents(0);
        CabinetOrderLine line = new CabinetOrderLine();
        line.setSkuId("SKU-A");
        line.setQuantity(1);
        line.setLineAmountCents(175);
        order.setLines(new ArrayList<>(List.of(line)));

        support.recalculatePayableAfterLineChange(order);

        assertEquals(525, order.getMemberDiscountCents(), "留档列不得被夹到 subtotal");
        assertEquals(175, order.getTotalAmountCents());
        assertEquals(700 - 525 - 0 - 0, order.getTotalAmountCents());
    }

    @Test
    @DisplayName("部分退：活动留档与会员留档一起等比缩")
    void proportionalRefund_scalesActivityArchive() {
        SettlementOrderSupport support = new SettlementOrderSupport(
                null, null, null, null, null, null, mock(CouponService.class), null,
                null, null, null, new OrderViewAssembler(), null);
        CabinetOrder order = new CabinetOrder();
        order.setMemberDiscountCents(100);
        order.setActivityDiscountCents(200);
        order.setCouponDiscountCents(0);

        support.applyProportionalRefundPayable(order, 1000, 500);

        assertEquals(50, order.getMemberDiscountCents());
        assertEquals(100, order.getActivityDiscountCents());
        assertEquals(500, order.getTotalAmountCents());
    }
}
