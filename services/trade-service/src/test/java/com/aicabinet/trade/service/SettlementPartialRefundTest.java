package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementPartialRefundTest {

    @Mock CabinetOrderMapper orderRepository;
    @Mock CabinetOrderLineMapper orderLineRepository;
    @Mock InventoryService inventoryService;
    @Mock OrderPaymentService orderPaymentService;
    @Mock RevenueSplitService revenueSplitService;
    @Mock CouponService couponService;

    SettlementService settlementService;

    @BeforeEach
    void setUp() {
        settlementService = new SettlementService(
                null, null, orderRepository, orderLineRepository,
                null, null, null, revenueSplitService,
                null, null, inventoryService, orderPaymentService,
                null, null, null, null, null, null,
                null, couponService, null, null, null, null,
                null, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(settlementService, "self", settlementService);
        lenient().doNothing().when(couponService).recalcOrRestoreAfterPartialRefund(any(), anyInt());
    }

    @Test
    void partialRefund_withCoupon_refundsPayableDeltaNotGross() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-C");
        order.setSessionId("S-C");
        order.setDeviceId("CAB-001");
        order.setUserId(10001L);
        order.setOriginalAmountCents(1000);
        order.setCouponId(99L);
        order.setCouponDiscountCents(200);
        order.setTotalAmountCents(800);
        order.setStatus("PAID");
        order.setInventoryDeducted(false);
        order.setLines(new ArrayList<>(List.of(
                line("SKU-A", "A", 1, 400, "B1"),
                line("SKU-B", "B", 2, 300, "B2"))));

        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(orderLineRepository).deleteByOrderId(anyString());
        doNothing().when(orderPaymentService).refundOrder(any(), anyInt(), anyString());
        doNothing().when(revenueSplitService).adjustSplitAfterPartialRefund(any(), eq(false));
        doAnswer(inv -> {
            CabinetOrder o = inv.getArgument(0);
            int sub = inv.getArgument(1);
            o.setOriginalAmountCents(sub);
            o.setCouponDiscountCents(200);
            o.setTotalAmountCents(Math.max(0, sub - 200));
            return null;
        }).when(couponService).recalcOrRestoreAfterPartialRefund(any(), eq(600));
        when(couponService.discountForOrderCoupon(99L, 600)).thenReturn(200);

        var result = settlementService.partialRefund(
                order,
                List.of(new OrderRefundRequest.PartialRefundLine("SKU-A", 1, false)),
                false,
                "coupon partial");

        assertEquals(400, result.refundedCents());
        assertEquals("PARTIAL_REFUNDED", result.status());
        assertEquals(400, order.getTotalAmountCents());
        verify(orderPaymentService).refundOrder(order, 400, "coupon partial");
    }

    @Test
    void partialRefund_oneOfTwoSkus_restoresOnlyChosenLine() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-1");
        order.setSessionId("S-1");
        order.setDeviceId("CAB-001");
        order.setUserId(10001L);
        order.setTotalAmountCents(1000);
        order.setStatus("PAID");
        order.setInventoryDeducted(true);
        order.setPayChannel("BALANCE");
        CabinetOrderLine a = line("SKU-A", "A", 1, 400, "B1");
        CabinetOrderLine b = line("SKU-B", "B", 2, 300, "B2");
        order.setLines(new ArrayList<>(List.of(a, b)));

        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(orderLineRepository).deleteByOrderId(anyString());
        when(orderLineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(orderPaymentService).refundOrder(any(), anyInt(), anyString());
        doNothing().when(revenueSplitService).adjustSplitAfterPartialRefund(any(), eq(false));
        doNothing().when(inventoryService).restoreForOrder(anyString(), anyList(), anyMap());
        doNothing().when(inventoryService).recordRefundKeptGoods(anyString(), anyList(), anyMap(), anyString());

        var result = settlementService.partialRefund(
                order,
                List.of(new OrderRefundRequest.PartialRefundLine("SKU-A", 1, true),
                        new OrderRefundRequest.PartialRefundLine("SKU-B", 1, false)),
                false,
                "partial line refund test");

        assertEquals(700, result.refundedCents()); // 400 + 300
        assertEquals("PARTIAL_REFUNDED", result.status());
        assertEquals(700, order.getRefundedCents());
        assertTrue(result.anyInventoryRestored());
        assertEquals(1, order.getLines().size());
        assertEquals("SKU-B", order.getLines().get(0).getSkuId());
        assertEquals(1, order.getLines().get(0).getQuantity());
        assertEquals(300, order.getTotalAmountCents());

        verify(inventoryService).restoreForOrder(
                eq("CAB-001"), argThat(list -> list != null && list.size() == 1), anyMap());
        verify(inventoryService).recordRefundKeptGoods(
                eq("CAB-001"), argThat(list -> list != null && list.size() == 1), anyMap(), eq("O-1"));
        verify(revenueSplitService, never()).adjustSplitAfterPartialRefund(any(), eq(true));
    }

    @Test
    void estimatePartialRefund_withCoupon_matchesPayableDelta() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-E");
        order.setOriginalAmountCents(1000);
        order.setCouponId(99L);
        order.setCouponDiscountCents(200);
        order.setTotalAmountCents(800);
        order.setLines(new ArrayList<>(List.of(
                line("SKU-A", "A", 1, 400, "B1"),
                line("SKU-B", "B", 2, 300, "B2"))));

        when(couponService.discountForOrderCoupon(99L, 600)).thenReturn(200);

        int estimate = settlementService.estimatePartialRefundCents(
                order,
                List.of(new OrderRefundRequest.PartialRefundLine("SKU-A", 1, false)));

        assertEquals(400, estimate);
        assertEquals(800, order.getTotalAmountCents(), "estimate must not mutate source order");
    }

    @Test
    void partialRefund_allLines_becomesFullRefunded() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-2");
        order.setSessionId("S-2");
        order.setDeviceId("CAB-001");
        order.setUserId(10001L);
        order.setTotalAmountCents(400);
        order.setStatus("PAID");
        order.setInventoryDeducted(true);
        order.setLines(new ArrayList<>(List.of(line("SKU-A", "A", 1, 400, "B1"))));

        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(orderLineRepository).deleteByOrderId(anyString());
        doNothing().when(orderPaymentService).refundOrder(any(), anyInt(), anyString());
        doNothing().when(revenueSplitService).adjustSplitAfterPartialRefund(any(), eq(true));
        doNothing().when(inventoryService).restoreForOrder(anyString(), anyList(), anyMap());

        var result = settlementService.partialRefund(
                order,
                List.of(new OrderRefundRequest.PartialRefundLine("SKU-A", 1, true)),
                true,
                "full via lines");

        assertEquals(400, result.refundedCents());
        assertEquals("REFUNDED", result.status());
        assertEquals(400, order.getRefundedCents());
        assertTrue(result.anyInventoryRestored());
        assertTrue(order.getLines().isEmpty());
        assertFalse(order.isInventoryDeducted());
        verify(revenueSplitService).adjustSplitAfterPartialRefund(order, true);
    }

    private static CabinetOrderLine line(String sku, String name, int qty, int unit, String batch) {
        CabinetOrderLine l = new CabinetOrderLine();
        l.setSkuId(sku);
        l.setSkuName(name);
        l.setQuantity(qty);
        l.setUnitPriceCents(unit);
        l.setLineAmountCents(unit * qty);
        l.setBatchNo(batch);
        return l;
    }
}
