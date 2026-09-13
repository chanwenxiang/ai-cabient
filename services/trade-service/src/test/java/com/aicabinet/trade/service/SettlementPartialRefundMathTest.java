package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementPartialRefundMathTest {

    @Test
    void mergeLinesBySku_sumsQuantityAndAmount() {
        Map<String, CabinetOrderLine> bySku = SettlementPartialRefundMath.mergeLinesBySku(List.of(
                line("A", 1, 100),
                line("A", 2, 200),
                line("B", 1, 50)));
        assertEquals(3, bySku.get("A").getQuantity());
        assertEquals(300, bySku.get("A").getLineAmountCents());
        assertEquals(1, bySku.get("B").getQuantity());
    }

    @Test
    void validatePartialRefundQuantities_rejectsOverQty() {
        Map<String, CabinetOrderLine> bySku = SettlementPartialRefundMath.mergeLinesBySku(
                List.of(line("A", 2, 200)));
        assertThrows(ResponseStatusException.class, () ->
                SettlementPartialRefundMath.validatePartialRefundQuantities(
                        bySku,
                        List.of(new OrderRefundRequest.PartialRefundLine("A", 3, null))));
    }

    @Test
    void buildRemainingLines_dropsFullyRefundedSku() {
        Map<String, CabinetOrderLine> bySku = SettlementPartialRefundMath.mergeLinesBySku(List.of(
                line("A", 2, 200),
                line("B", 1, 100)));
        List<CabinetOrderLine> remaining = SettlementPartialRefundMath.buildRemainingLines(
                bySku, Map.of("A", 2, "B", 0));
        assertEquals(1, remaining.size());
        assertEquals("B", remaining.get(0).getSkuId());
        assertEquals(1, remaining.get(0).getQuantity());
    }

    @Test
    void copyOrderForPartialRefundEstimate_isDeepEnough() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-1");
        order.setTotalAmountCents(300);
        order.setLines(List.of(line("A", 1, 300)));
        CabinetOrder copy = SettlementPartialRefundMath.copyOrderForPartialRefundEstimate(order);
        copy.getLines().get(0).setQuantity(9);
        assertEquals(1, order.getLines().get(0).getQuantity());
        assertTrue(copy.getLines().get(0).getQuantity() == 9);
    }

    private static CabinetOrderLine line(String sku, int qty, int amount) {
        CabinetOrderLine line = new CabinetOrderLine();
        line.setSkuId(sku);
        line.setSkuName(sku);
        line.setQuantity(qty);
        line.setUnitPriceCents(amount / Math.max(1, qty));
        line.setLineAmountCents(amount);
        return line;
    }
}
