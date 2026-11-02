package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.service.view.OrderViewAssembler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    /**
     * MA-067（活栈实测）：扣款失败转 PENDING 时「不占券」必须**落到库**。
     *
     * <p>{@code save} 在订单已 insert 过之后走 MP 的 {@code updateById}，null 字段被跳过 ⇒
     * 实体上的 {@code setCouponId(null)} 根本写不进去，库里留着 coupon_id 而 user_coupon 仍是
     * UNUSED（券没核销却挂在单上），后续按 {@code order.getCouponId()} 重算折扣的链路会对
     * 一张没收的券再折一次。判据 = 显式 UPDATE {@code clearOrderCouponSelection} 被调用，
     * 且金额是还原后的折前应付。</p>
     */
    @Test
    @DisplayName("扣款失败转 PENDING：显式 UPDATE 摘券（updateById 会跳过 null）")
    void chargeFailPending_issuesExplicitCouponClearUpdate() {
        com.aicabinet.trade.mapper.ShoppingSessionMapper sessionRepository =
                mock(com.aicabinet.trade.mapper.ShoppingSessionMapper.class);
        com.aicabinet.trade.mapper.CabinetOrderMapper orderRepository =
                mock(com.aicabinet.trade.mapper.CabinetOrderMapper.class);
        com.aicabinet.trade.mapper.CabinetOrderLineMapper orderLineRepository =
                mock(com.aicabinet.trade.mapper.CabinetOrderLineMapper.class);
        DeviceValidationService deviceValidationService = mock(DeviceValidationService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        OrderPaymentService orderPaymentService = mock(OrderPaymentService.class);
        UserValidationService userValidationService = mock(UserValidationService.class);
        CouponService couponService = mock(CouponService.class);
        MemberService memberService = mock(MemberService.class);
        RevenueSplitService revenueSplitService = mock(RevenueSplitService.class);
        NotificationService notificationService = mock(NotificationService.class);
        DisplaySnapshotHelper displaySnapshotHelper = mock(DisplaySnapshotHelper.class);
        SettlementOrderSupport orderSupport = mock(SettlementOrderSupport.class);
        OpsExceptionService opsExceptionService = mock(OpsExceptionService.class);
        RiskControlService riskControlService = mock(RiskControlService.class);

        SettlementOrderFinalizeService service = new SettlementOrderFinalizeService(
                sessionRepository, orderRepository, orderLineRepository, deviceValidationService,
                inventoryService, orderPaymentService, userValidationService, couponService, memberService,
                revenueSplitService, notificationService, displaySnapshotHelper,
                orderSupport, opsExceptionService, riskControlService);

        // 行金额=会员折后 343（无活动），券 ¥2 ⇒ 选中后应付 143；扣款 412 失败 ⇒ 还原 343 并摘券
        org.mockito.Mockito.doAnswer(inv -> {
            CabinetOrder o = inv.getArgument(0);
            o.setGrossAmountCents(350);
            o.setMemberDiscountCents(7);
            o.setOriginalAmountCents(343);
            o.setTotalAmountCents(343);
            CabinetOrderLine line = new CabinetOrderLine();
            line.setSkuId("100013");
            line.setQuantity(1);
            line.setLineAmountCents(343);
            o.setLines(new ArrayList<>(List.of(line)));
            return null;
        }).when(orderSupport).applyItemsToOrder(any(CabinetOrder.class), anyList());
        when(couponService.selectPreferredOrBest(anyLong(), any(), anyInt(), any()))
                .thenReturn(java.util.Optional.of(new CouponService.BestCoupon(4L, 200, null)));
        when(userValidationService.canChargeViaPasswordFree(anyLong(), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.PRECONDITION_FAILED, ApiMessages.INSUFFICIENT_BALANCE))
                .when(orderPaymentService).chargeOrder(any(CabinetOrder.class));

        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-MA067");
        session.setUserId(10001L);
        session.setDeviceId("DEV-MA067");
        service.finalizeOrder(session, List.of(new com.aicabinet.trade.recognition.RecognizedItem("100013", 1, 1f)));

        verify(orderRepository).clearOrderCouponSelection(anyString(), eq(343), any(java.time.Instant.class));
    }
}
