package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefundInventoryPolicyTest {

    @Test
    void explicitFlagWins() {
        assertTrue(RefundInventoryPolicy.resolve(true, "质量问题已拿走", false));
        assertFalse(RefundInventoryPolicy.resolve(false, "我没有拿这个商品", true));
    }

    @Test
    void recognitionReasonRestores() {
        assertTrue(RefundInventoryPolicy.resolve(null, "我没有拿这个商品，请核对识别结果", false));
        assertTrue(RefundInventoryPolicy.resolve(null, "疑似重复扣款", false));
    }

    @Test
    void keptGoodsDoesNotRestore() {
        assertFalse(RefundInventoryPolicy.resolve(null, "商品质量问题，货已拿走，申请仅退款不退货", true));
        assertFalse(RefundInventoryPolicy.resolve(null, "仅退款不回库", true));
    }

    @Test
    void unknownFallsBackToDefault() {
        assertTrue(RefundInventoryPolicy.resolve(null, "其他原因说明文字", true));
        assertFalse(RefundInventoryPolicy.resolve(null, "其他原因说明文字", false));
    }
}
