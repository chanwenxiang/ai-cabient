package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.recognition.RecognizedItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R20-2（2026-11-13）：结算成功但**钱没实收**（微信支付分免密腿：只「完结订单」，扣款异步）时，
 * 会员消费统计/返积分与 order_paid 通知必须推迟到实收确认点；分账行仍要建（不入钱包由
 * {@link RevenueSplitServiceTest} 那组用例钉）。
 *
 * <p>这两腿分开钉很重要：只挪分账不挪积分，用户 EXPIRED/REVOKED 之后手里还留着积分与
 * 「已支付」通知，而取消路径全仓没有任何追回动作。
 */
class SettlementUncollectedLegsTest {

    private record Fixture(
            SettlementOrderFinalizeService service,
            CabinetOrderMapper orderRepository,
            MemberService memberService,
            NotificationService notificationService,
            RevenueSplitService revenueSplitService,
            OrderPaymentService orderPaymentService) {
    }

    /** 让 chargeOrder 像支付分腿那样「不抛异常」，并把订单留在指定状态。 */
    private Fixture fixtureWithStatusAfterCharge(String statusAfterCharge) {
        ShoppingSessionMapper sessionRepository = mock(ShoppingSessionMapper.class);
        CabinetOrderMapper orderRepository = mock(CabinetOrderMapper.class);
        CabinetOrderLineMapper orderLineRepository = mock(CabinetOrderLineMapper.class);
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

        doAnswer(inv -> {
            CabinetOrder o = inv.getArgument(0);
            o.setGrossAmountCents(350);
            o.setOriginalAmountCents(350);
            o.setTotalAmountCents(350);
            CabinetOrderLine line = new CabinetOrderLine();
            line.setSkuId("100013");
            line.setQuantity(1);
            line.setLineAmountCents(350);
            o.setLines(new ArrayList<>(List.of(line)));
            return null;
        }).when(orderSupport).applyItemsToOrder(any(CabinetOrder.class), anyList());
        when(couponService.selectPreferredOrBest(anyLong(), any(), anyInt(), any()))
                .thenReturn(Optional.empty());
        when(userValidationService.canChargeViaPasswordFree(anyLong(), any())).thenReturn(true);
        doAnswer(inv -> {
            ((CabinetOrder) inv.getArgument(0)).setStatus(statusAfterCharge);
            return null;
        }).when(orderPaymentService).chargeOrder(any(CabinetOrder.class));

        return new Fixture(service, orderRepository, memberService, notificationService,
                revenueSplitService, orderPaymentService);
    }

    private void settle(Fixture fx) {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-R202");
        session.setUserId(10001L);
        session.setDeviceId("DEV-R202");
        fx.service().finalizeOrder(session, List.of(new RecognizedItem("100013", 1, 1f)));
    }

    @Test
    @DisplayName("支付分腿：结算返回成功但订单仍 PENDING ⇒ 不发积分/统计、不发 order_paid 通知")
    void payscorePendingLeg_defersMemberStatsAndPaidNotification() {
        Fixture fx = fixtureWithStatusAfterCharge("PENDING");

        settle(fx);

        // 分账行仍要建（入账时点由 OrderCollectionGate 在 RevenueSplitService 内部拦）
        verify(fx.revenueSplitService()).recordSplit(any(CabinetOrder.class));
        verifyNoInteractions(fx.memberService());
        verifyNoInteractions(fx.notificationService());
    }

    @Test
    @DisplayName("同步渠道：结算即 PAID ⇒ 两腿照旧执行（防我把门加反方向）")
    void collectedLeg_stillGrantsMemberStatsAndNotifies() {
        Fixture fx = fixtureWithStatusAfterCharge("PAID");

        settle(fx);

        verify(fx.memberService()).onOrderPaid(eq(10001L), eq(350), anyString());
        verify(fx.notificationService()).notifyConsumer(
                eq(10001L),
                eq("order_paid"),
                any(),
                eq("ORDER"),
                anyString());
    }
}
