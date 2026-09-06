package com.aicabinet.trade.reconciliation;

import com.aicabinet.trade.domain.CabinetOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockPlatformBillProviderTest {

    @Test
    void excludesBalancePaidOrdersFromGatewayMockBill() {
        assertFalse(MockPlatformBillProvider.isGatewayBillableOrder(order("BALANCE", "PAID")));
        assertFalse(MockPlatformBillProvider.isGatewayBillableOrder(order("balance", "PAID")));
    }

    @Test
    void includesWechatAndAlipayPaidOrders() {
        assertTrue(MockPlatformBillProvider.isGatewayBillableOrder(order("WECHAT", "PAID")));
        assertTrue(MockPlatformBillProvider.isGatewayBillableOrder(order("ALIPAY", "PAID")));
        assertTrue(MockPlatformBillProvider.isGatewayBillableOrder(order("MOCK", "PAID")));
        assertTrue(MockPlatformBillProvider.isGatewayBillableOrder(order("WECHAT", "REFUNDED")));
    }

    @Test
    void excludesUnpaidOrNull() {
        assertFalse(MockPlatformBillProvider.isGatewayBillableOrder(order("WECHAT", "PENDING")));
        assertFalse(MockPlatformBillProvider.isGatewayBillableOrder(null));
    }

    @Test
    void parsesRechargeOrderIdFromRefundIdempotency() {
        assertEquals("1788597496253245468",
                MockPlatformBillProvider.rechargeOrderIdFromIdempotency(
                        "RECHARGE-REFUND:1788597496253245468:2000:RF89000525791141"));
        assertEquals("ABC",
                MockPlatformBillProvider.rechargeOrderIdFromIdempotency("recharge-refund:ABC"));
        assertNull(MockPlatformBillProvider.rechargeOrderIdFromIdempotency("other:x"));
    }

    private static CabinetOrder order(String channel, String status) {
        CabinetOrder o = new CabinetOrder();
        o.setPayChannel(channel);
        o.setStatus(status);
        o.setOrderId("ORD-1");
        o.setTotalAmountCents(100);
        return o;
    }
}
