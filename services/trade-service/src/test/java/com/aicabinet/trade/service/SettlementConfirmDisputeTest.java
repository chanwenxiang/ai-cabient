package com.aicabinet.trade.service;

import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceSlotMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementConfirmDisputeTest {

    @Mock CabinetOrderMapper orderRepository;
    @Mock CabinetOrderLineMapper orderLineRepository;
    @Mock SkuCatalogMapper skuCatalogRepository;
    @Mock DeviceSlotMapper slotRepository;
    @Mock MerchantSkuPricingService skuPricingService;
    @Mock MemberService memberService;
    @Mock UserValidationService userValidationService;
    @Mock InventoryService inventoryService;
    @Mock OrderPaymentService orderPaymentService;
    @Mock RevenueSplitService revenueSplitService;
    @Mock CouponService couponService;
    @Mock ShoppingSessionMapper sessionRepository;
    @Mock DistributedLockService distributedLockService;

    SettlementService settlementService;

    @BeforeEach
    void setUp() {
        settlementService = new SettlementService(
                sessionRepository, skuCatalogRepository, orderRepository, orderLineRepository,
                null, null, null, revenueSplitService,
                null, null, inventoryService, orderPaymentService,
                null, null, null, skuPricingService, userValidationService,
                null, null, couponService, memberService, null, null,
                slotRepository, null, null, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(settlementService, "self", settlementService);
        lenient().when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L))).thenReturn(true);
        lenient().when(sessionRepository.findByIdForUpdate(anyString())).thenAnswer(inv -> {
            ShoppingSession s = new ShoppingSession();
            s.setSessionId(inv.getArgument(0));
            return Optional.of(s);
        });
    }

    @Test
    void confirmDisputedItems_increase_callsAdjustSplitWithOriginalPayable() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-D1");
        session.setUserId(10001L);
        session.setDeviceId("CAB-1");

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-D1");
        order.setSessionId("S-D1");
        order.setUserId(10001L);
        order.setDeviceId("CAB-1");
        order.setStatus("DISPUTED");
        order.setTotalAmountCents(400);
        order.setInventoryDeducted(true);
        order.setLines(new ArrayList<>(List.of(line("SKU-A", "A", 1, 400))));

        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId("SKU-A");
        sku.setSkuName("A");

        when(orderRepository.findBySessionId("S-D1")).thenReturn(Optional.of(order));
        when(skuCatalogRepository.findById("SKU-A")).thenReturn(Optional.of(sku));
        when(skuPricingService.resolveUnitPriceCents("CAB-1", sku)).thenReturn(400);
        when(memberService.applyMemberPriceDiscount(10001L, 400)).thenReturn(400);
        when(slotRepository.findByIdDeviceId("CAB-1")).thenReturn(List.of());
        when(userValidationService.canChargeViaPasswordFree(10001L, null)).thenReturn(true);
        when(inventoryService.adjustForOrder(anyString(), anyList(), anyList(), anyMap())).thenReturn(Map.of());
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(orderLineRepository).deleteByOrderId(anyString());
        when(orderLineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(orderPaymentService).applyPaymentDelta(any(), anyInt());

        var result = settlementService.confirmDisputedItems(
                session,
                List.of(new VisionServiceClient.RecognizedItem("SKU-A", 2, 1f)));

        assertEquals(400, result.originalAmountCents());
        assertEquals(800, result.finalAmountCents());
        assertEquals(400, result.adjustmentCents());
        assertEquals("PAID", order.getStatus());
        verify(revenueSplitService).adjustSplitAfterOrderChange(order, 400);
        verify(orderPaymentService).applyPaymentDelta(order, 400);
    }

    @Test
    void confirmDisputedItems_decrease_callsAdjustSplitWithOriginalPayable() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-D2");
        session.setUserId(10001L);
        session.setDeviceId("CAB-1");

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-D2");
        order.setSessionId("S-D2");
        order.setUserId(10001L);
        order.setDeviceId("CAB-1");
        order.setStatus("DISPUTED");
        order.setTotalAmountCents(800);
        order.setInventoryDeducted(true);
        order.setLines(new ArrayList<>(List.of(line("SKU-A", "A", 2, 400))));

        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId("SKU-A");
        sku.setSkuName("A");

        when(orderRepository.findBySessionId("S-D2")).thenReturn(Optional.of(order));
        when(skuCatalogRepository.findById("SKU-A")).thenReturn(Optional.of(sku));
        when(skuPricingService.resolveUnitPriceCents("CAB-1", sku)).thenReturn(400);
        when(memberService.applyMemberPriceDiscount(10001L, 400)).thenReturn(400);
        when(slotRepository.findByIdDeviceId("CAB-1")).thenReturn(List.of());
        when(inventoryService.adjustForOrder(anyString(), anyList(), anyList(), anyMap())).thenReturn(Map.of());
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(orderLineRepository).deleteByOrderId(anyString());
        when(orderLineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(orderPaymentService).applyPaymentDelta(any(), eq(-400));

        var result = settlementService.confirmDisputedItems(
                session,
                List.of(new VisionServiceClient.RecognizedItem("SKU-A", 1, 1f)));

        assertEquals(800, result.originalAmountCents());
        assertEquals(400, result.finalAmountCents());
        assertEquals(-400, result.adjustmentCents());
        verify(revenueSplitService).adjustSplitAfterOrderChange(order, 800);
        verify(orderPaymentService).applyPaymentDelta(order, -400);
    }

    @Test
    void confirmDisputedItems_withCoupon_recalculatesPayableAndAdjustsSplit() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-D3");
        session.setUserId(10001L);
        session.setDeviceId("CAB-1");

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-D3");
        order.setSessionId("S-D3");
        order.setUserId(10001L);
        order.setDeviceId("CAB-1");
        order.setStatus("DISPUTED");
        order.setCouponId(99L);
        order.setCouponDiscountCents(200);
        order.setOriginalAmountCents(800);
        order.setTotalAmountCents(600);
        order.setInventoryDeducted(true);
        order.setLines(new ArrayList<>(List.of(line("SKU-A", "A", 2, 400))));

        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId("SKU-A");
        sku.setSkuName("A");

        when(orderRepository.findBySessionId("S-D3")).thenReturn(Optional.of(order));
        when(skuCatalogRepository.findById("SKU-A")).thenReturn(Optional.of(sku));
        when(skuPricingService.resolveUnitPriceCents("CAB-1", sku)).thenReturn(400);
        when(memberService.applyMemberPriceDiscount(10001L, 400)).thenReturn(400);
        when(slotRepository.findByIdDeviceId("CAB-1")).thenReturn(List.of());
        when(couponService.discountForOrderCoupon(99L, 1200)).thenReturn(200);
        when(userValidationService.canChargeViaPasswordFree(10001L, null)).thenReturn(true);
        when(inventoryService.adjustForOrder(anyString(), anyList(), anyList(), anyMap())).thenReturn(Map.of());
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(orderLineRepository).deleteByOrderId(anyString());
        when(orderLineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(orderPaymentService).applyPaymentDelta(any(), eq(400));

        var result = settlementService.confirmDisputedItems(
                session,
                List.of(new VisionServiceClient.RecognizedItem("SKU-A", 3, 1f)));

        assertEquals(600, result.originalAmountCents());
        assertEquals(1000, result.finalAmountCents());
        assertEquals(400, result.adjustmentCents());
        assertEquals(200, order.getCouponDiscountCents());
        verify(revenueSplitService).adjustSplitAfterOrderChange(order, 600);
    }

    @Test
    void confirmDisputedItems_withMemberDiscount_preservesDiscountOnRecalc() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-D4");
        session.setUserId(10001L);
        session.setDeviceId("CAB-1");

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-D4");
        order.setSessionId("S-D4");
        order.setUserId(10001L);
        order.setDeviceId("CAB-1");
        order.setStatus("DISPUTED");
        order.setMemberDiscountCents(100);
        order.setOriginalAmountCents(800);
        order.setTotalAmountCents(700);
        order.setInventoryDeducted(true);
        order.setLines(new ArrayList<>(List.of(line("SKU-A", "A", 2, 400))));

        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId("SKU-A");
        sku.setSkuName("A");

        when(orderRepository.findBySessionId("S-D4")).thenReturn(Optional.of(order));
        when(skuCatalogRepository.findById("SKU-A")).thenReturn(Optional.of(sku));
        when(skuPricingService.resolveUnitPriceCents("CAB-1", sku)).thenReturn(400);
        when(memberService.applyMemberPriceDiscount(10001L, 400)).thenReturn(400);
        when(slotRepository.findByIdDeviceId("CAB-1")).thenReturn(List.of());
        when(inventoryService.adjustForOrder(anyString(), anyList(), anyList(), anyMap())).thenReturn(Map.of());
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(orderLineRepository).deleteByOrderId(anyString());
        when(orderLineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(orderPaymentService).applyPaymentDelta(any(), eq(400));

        var result = settlementService.confirmDisputedItems(
                session,
                List.of(new VisionServiceClient.RecognizedItem("SKU-A", 3, 1f)));

        assertEquals(700, result.originalAmountCents());
        assertEquals(1100, result.finalAmountCents());
        assertEquals(400, result.adjustmentCents());
        assertEquals(100, order.getMemberDiscountCents());
        assertEquals(1100, order.getTotalAmountCents());
        verify(revenueSplitService).adjustSplitAfterOrderChange(order, 700);
    }

    private static CabinetOrderLine line(String sku, String name, int qty, int unit) {
        CabinetOrderLine l = new CabinetOrderLine();
        l.setSkuId(sku);
        l.setSkuName(name);
        l.setQuantity(qty);
        l.setUnitPriceCents(unit);
        l.setLineAmountCents(unit * qty);
        return l;
    }
}
