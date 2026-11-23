package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MA-397：充值退款 reason 必须保住「尾部 #orderId」供对账查单器反解。 */
class PaymentServiceReasonSuffixTest {

    @Test
    void longReason_keepsOrderIdSuffixWithinLimit() {
        String base = "x".repeat(200);

        String out = PaymentService.rechargeRefundReasonWithOrderId(base, "R20260101001", 128);

        assertTrue(out.length() <= 128, "reason 不得超过 128：" + out.length());
        assertTrue(out.endsWith(" #R20260101001"), "尾缀被截掉，告警 SQL 永远 JOIN 不上：" + out);
    }

    @Test
    void reasonAlreadyContainingOrderId_unchanged() {
        String base = "已有 #R20260101001 的说明";

        assertEquals(base, PaymentService.rechargeRefundReasonWithOrderId(base, "R20260101001", 128));
    }

    @Test
    void emojiBoundary_notSplit() {
        String base = "🍎".repeat(100);

        String out = PaymentService.rechargeRefundReasonWithOrderId(base, "R20260101001", 128);

        assertTrue(out.endsWith(" #R20260101001"));
        String suffix = " #R20260101001";
        char lastBody = out.charAt(out.length() - suffix.length() - 1);
        assertFalse(Character.isHighSurrogate(lastBody), "截断不得留下孤代理（emoji 被切半）");
    }
}