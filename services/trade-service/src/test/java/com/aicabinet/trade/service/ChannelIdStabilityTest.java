package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.RechargeOrder;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A2（lessons #97/#225）：渠道单号与幂等键只由不变业务要素构成，缺失即拒绝。 */
class ChannelIdStabilityTest {

    @Test
    void idemKeyIsStableAcrossRetriesAndUniquePerRequest() {
        String first = PayoutAccountService.newIdemKey("MW", 42L);

        // 🔴 形态受渠道契约约束：微信 out_bill_no 只收数字/大小写字母、6–32 位。
        //    旧实现 "MW:42" 含冒号 ⇒ 真打款必然 PARAM_ERROR（2026-11-23 复核）。
        assertEquals("MW0000000042", first);
        assertEquals(first, PayoutAccountService.newIdemKey("MW", 42L));
        assertNotEquals(first, PayoutAccountService.newIdemKey("MW", 43L));
        assertNotEquals(first, PayoutAccountService.newIdemKey("LW", 42L));
        assertTrue(PayoutAccountService.isChannelSafeBillNo(first));
        assertTrue(PayoutAccountService.isChannelSafeBillNo(
                PayoutAccountService.newIdemKey("LW", 1L)));
        assertFalse(PayoutAccountService.isChannelSafeBillNo("MW:42"));
        assertFalse(PayoutAccountService.isChannelSafeBillNo("MW42"));
    }

    @Test
    void refundNoMustBeProvided() {
        assertEquals("RF-1", PaymentService.requireStableRefundNo(" RF-1 "));
        assertThrows(ResponseStatusException.class, () -> PaymentService.requireStableRefundNo(null));
        assertThrows(ResponseStatusException.class, () -> PaymentService.requireStableRefundNo("   "));
    }

    @Test
    void fullRefundNoIsDerivedFromOrderId() {
        RechargeOrder order = new RechargeOrder();
        order.setOrderId("R-9");

        assertEquals("RF-FULL-R-9", PaymentService.fullRefundNo(order));
        assertEquals(PaymentService.fullRefundNo(order), PaymentService.fullRefundNo(order));
    }
}
