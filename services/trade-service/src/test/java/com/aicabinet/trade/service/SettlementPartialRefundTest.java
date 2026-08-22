package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
                null, null);
        lenient().doNothing().when(couponService).recalcOrRestoreAfterPartialRefund(any(), anyInt());
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
        doNothing().when(revenueSplitService).resyncSplitForOrder(any());
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

        ArgumentCaptor<List> restoreCap = ArgumentCaptor.forClass(List.class);
        verify(inventoryService).restoreForOrder(eq("CAB-001"), restoreCap.capture(), anyMap());
        assertEquals(1, restoreCap.getValue().size());
        ArgumentCaptor<List> keptCap = ArgumentCaptor.forClass(List.class);
        verify(inventoryService).recordRefundKeptGoods(eq("CAB-001"), keptCap.capture(), anyMap(), eq("O-1"));
        assertEquals(1, keptCap.getValue().size());
        verify(revenueSplitService, never()).voidSplitOnFullRefund(anyString());
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
        doNothing().when(revenueSplitService).voidSplitOnFullRefund(anyString());
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
        verify(revenueSplitService).voidSplitOnFullRefund("O-2");
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
