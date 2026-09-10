package com.aicabinet.trade.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicabinet.common.util.DisputeConsumerCopy;
import org.junit.jupiter.api.Test;

class DisputeConsumerCopyTest {

    @Test
    void statusLabel_open() {
        assertEquals("审核中 · 暂未扣款", DisputeConsumerCopy.statusLabel("OPEN", 100, 0));
    }

    @Test
    void statusLabel_resolvedWithBillAndRefund() {
        assertEquals("已结案 · 扣款 ¥12.30 / 退款 ¥1.00",
                DisputeConsumerCopy.statusLabel("RESOLVED", 1230, 100));
    }

    @Test
    void reviewCopy_resolved() {
        var copy = DisputeConsumerCopy.reviewCopy("RESOLVED", "anything", 500, 0);
        assertEquals("success", copy.tone());
        assertEquals("人工审核已完成", copy.title());
        assertTrue(copy.detail().contains("¥5.00"));
    }

    @Test
    void amountDiffNote_withMemberAndCoupon() {
        String note = DisputeConsumerCopy.amountDiffNote(1000, 700, 200, 100);
        assertEquals("识别参考 ¥10.00，实扣 ¥7.00（会员优惠 ¥2.00 + 优惠券 ¥1.00）", note);
    }

    @Test
    void amountDiffNote_noneWhenEqual() {
        assertEquals("", DisputeConsumerCopy.amountDiffNote(100, 100, 0, 0));
    }
}
