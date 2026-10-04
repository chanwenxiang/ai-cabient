package com.aicabinet.trade.service.view;

import com.aicabinet.common.dto.OrderReadModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OrderPaymentLedgerTest {

    @Test
    void apply_usesPaymentOpsNotInflatedOrderRefunded() {
        OrderReadModel dirty = new OrderReadModel(
                "O1", "S1", 1L, "D1", "M1", "柜", "商",
                0, 0, 0, 0, "REFUNDED", "BALANCE", 0, "",
                null, "BL-1", null, 1200, false, null, null, null, null, null, null, null);

        OrderReadModel view = OrderPaymentLedger.apply(dirty, 800, 400);

        assertEquals(400, view.totalAmountCents());
        assertEquals(800, view.originalAmountCents());
        assertEquals(400, view.refundedCents());
        assertEquals("PARTIAL_REFUNDED", view.status());
    }

    @Test
    void apply_fullRefundWhenChargeEqualsRefund() {
        OrderReadModel dirty = new OrderReadModel(
                "O1", "S1", 1L, "D1", "M1", "柜", "商",
                0, 800, 0, 0, "PAID", "BALANCE", 0, "",
                null, null, null, 0, false, null, null, null, null, null, null, null);

        OrderReadModel view = OrderPaymentLedger.apply(dirty, 800, 800);

        assertEquals(0, view.totalAmountCents());
        assertEquals(800, view.refundedCents());
        assertEquals("REFUNDED", view.status());
        assertEquals(800, view.originalAmountCents());
    }

    @Test
    void apply_noOpsLeavesModel() {
        OrderReadModel model = new OrderReadModel(
                "O1", "S1", 1L, "D1", "M1", "柜", "商",
                450, 450, 0, 0, "PAID", "BALANCE", 1, "奶",
                null, null, null, 0, false, null, null, null, null, null, null, null);
        assertEquals(model, OrderPaymentLedger.apply(model, 0, 0));
        assertNull(OrderPaymentLedger.apply(null, 800, 400));
    }
}
