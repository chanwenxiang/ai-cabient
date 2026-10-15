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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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
    @Mock DisplaySnapshotHelper displaySnapshotHelper;

    SettlementService settlementService;

    @BeforeEach
    void setUp() {
        var orderViewAssembler = new com.aicabinet.trade.service.view.OrderViewAssembler();
        SettlementOrderSupport orderSupport = new SettlementOrderSupport(
                null, orderRepository, orderLineRepository, null,
                null, null, couponService, null,
                revenueSplitService, orderPaymentService, null, orderViewAssembler);
        settlementService = new SettlementService(
                null, orderRepository,
                null, null, null, null, null, null,
                orderSupport, null);
        SettlementPartialRefundService partialRefundService = new SettlementPartialRefundService(
                orderRepository, orderPaymentService, inventoryService, revenueSplitService,
                couponService, orderSupport, null);
        org.springframework.test.util.ReflectionTestUtils.setField(partialRefundService, "self", partialRefundService);
        org.springframework.test.util.ReflectionTestUtils.setField(
                settlementService, "settlementPartialRefundService", partialRefundService);
        lenient().when(couponService.settleAfterPartialRefund(any(), anyInt(), anyInt())).thenReturn(0);
    }

    @Test
    void partialRefund_withCoupon_proratesDiscountBySubtotalRatio() {
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
        when(orderRepository.findById("O-C")).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate("O-C")).thenReturn(Optional.of(order));
        doNothing().when(orderLineRepository).deleteByOrderId(anyString());
        doNothing().when(orderPaymentService).refundOrder(any(), anyInt(), anyString());
        doNothing().when(revenueSplitService).adjustSplitAfterPartialRefund(any(), eq(false));
        // CB-045 拍板（政府消费券式+比例摊）：oldSubtotal=1000 → newSubtotal=600（退 SKU-A 400），
        // 券抵扣按比例 200×0.6=120（不返券、不重算门槛），退款=800−(600−120)=320
        when(couponService.settleAfterPartialRefund(any(), eq(1000), eq(600))).thenReturn(120);

        var result = settlementService.partialRefund(
                order,
                List.of(new OrderRefundRequest.PartialRefundLine("SKU-A", 1, false)),
                false,
                "coupon partial");

        assertEquals(320, result.refundedCents());
        assertEquals("PARTIAL_REFUNDED", result.status());
        assertEquals(480, order.getTotalAmountCents());
        assertEquals(120, order.getCouponDiscountCents());
        verify(couponService).settleAfterPartialRefund(order, 1000, 600);
        verify(orderPaymentService).refundOrder(order, 320, "coupon partial");
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
        when(orderRepository.findById("O-1")).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate("O-1")).thenReturn(Optional.of(order));
        doNothing().when(orderLineRepository).deleteByOrderId(anyString());
        when(orderLineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(orderPaymentService).refundOrder(any(), anyInt(), anyString());
        doNothing().when(revenueSplitService).adjustSplitAfterPartialRefund(any(), eq(false));
        doNothing().when(inventoryService).restoreForOrder(anyString(), anyList(), anyMap(), anyString());
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
                eq("CAB-001"), argThat(list -> list != null && list.size() == 1), anyMap(), eq("O-1"));
        verify(inventoryService).recordRefundKeptGoods(
                eq("CAB-001"), argThat(list -> list != null && list.size() == 1), anyMap(), eq("O-1"));
        verify(revenueSplitService, never()).adjustSplitAfterPartialRefund(any(), eq(true));
    }

    @Test
    void estimatePartialRefund_withCoupon_proratesByRatio_matchingWriteSide() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-E");
        order.setOriginalAmountCents(1000);
        order.setCouponId(99L);
        order.setCouponDiscountCents(200);
        order.setTotalAmountCents(800);
        order.setLines(new ArrayList<>(List.of(
                line("SKU-A", "A", 1, 400, "B1"),
                line("SKU-B", "B", 2, 300, "B2"))));

        // CB-045 比例摊预估（纯函数，不读 couponService）：与写侧同口径（铁律 51）
        int estimate = settlementService.estimatePartialRefundCents(
                order,
                List.of(new OrderRefundRequest.PartialRefundLine("SKU-A", 1, false)));

        assertEquals(320, estimate);
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
        when(orderRepository.findById("O-2")).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForUpdate("O-2")).thenReturn(Optional.of(order));
        doNothing().when(orderLineRepository).deleteByOrderId(anyString());
        doNothing().when(orderPaymentService).refundOrder(any(), anyInt(), anyString());
        doNothing().when(revenueSplitService).adjustSplitAfterPartialRefund(any(), eq(true));
        doNothing().when(inventoryService).restoreForOrder(anyString(), anyList(), anyMap(), anyString());

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

    /** C04：渠道退款失败时本地零改动——不落库、不动库存/行/分账。 */
    @Test
    void partialRefund_channelFails_localUntouched() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-C04");
        order.setSessionId("S-C04");
        order.setDeviceId("CAB-001");
        order.setUserId(10001L);
        order.setTotalAmountCents(400);
        order.setStatus("PAID");
        order.setInventoryDeducted(true);
        order.setLines(new ArrayList<>(List.of(line("SKU-A", "A", 1, 400, "B1"))));

        when(orderRepository.findById("O-C04")).thenReturn(Optional.of(order));
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "渠道退款失败"))
                .when(orderPaymentService).refundOrder(any(), anyInt(), anyString());

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> settlementService.partialRefund(
                        order,
                        List.of(new OrderRefundRequest.PartialRefundLine("SKU-A", 1, true)),
                        true,
                        "c04 channel fail"));

        verify(orderRepository, never()).save(any());
        verify(orderRepository, never()).findByIdForUpdate(anyString());
        verify(orderLineRepository, never()).deleteByOrderId(anyString());
        verify(inventoryService, never()).restoreForOrder(anyString(), anyList(), anyMap(), anyString());
        verify(revenueSplitService, never()).adjustSplitAfterPartialRefund(any(), anyBoolean());
    }

    /** CB-045：比例摊纯函数钉住——递推多次部分退等价于一次性比例摊（每元摊销恒定）。 */
    @Test
    void proportionalProrationMath_recursiveEqualsOneShot() {
        // 1000 元、券 200：先退一半（500）再退一半（250 之外的 250）——递推 200×0.5=100 → 100×0.5=50
        int step1 = SettlementOrderSupport.scaleDiscountForPartialRefund(200, 1000, 500);
        int step2 = SettlementOrderSupport.scaleDiscountForPartialRefund(step1, 500, 250);
        assertEquals(50, step2);
        // 一次性等价：200×0.25=50
        assertEquals(50, SettlementOrderSupport.scaleDiscountForPartialRefund(200, 1000, 250));
        // 只减不增 + 边界
        assertEquals(0, SettlementOrderSupport.scaleDiscountForPartialRefund(200, 1000, 0));
        assertEquals(0, SettlementOrderSupport.scaleDiscountForPartialRefund(0, 1000, 500));
        assertEquals(200, SettlementOrderSupport.scaleDiscountForPartialRefund(200, 500, 1000));
        assertEquals(50, CouponService.proportionalCouponDiscount(200, 1000, 250));
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
