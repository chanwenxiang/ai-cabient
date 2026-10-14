package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.OrderRevenueSplit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日结通知聚合口径（CB-020 补充，2026-10-14 用户拍板）：
 * 冲正单不计、null/空商户不计、负数按 0、**0.00 商户不发**（与老系统全量推送有意不同）。
 */
class MerchantDailyIncomeNotifySchedulerTest {

    private OrderRevenueSplit split(String merchantId, String status, long merchantCents) {
        OrderRevenueSplit s = new OrderRevenueSplit();
        s.setMerchantId(merchantId);
        s.setStatus(status);
        s.setMerchantCents(merchantCents);
        return s;
    }

    @Test
    void aggregatesPerMerchantAndDropsZeroIncome() {
        Map<String, Long> m = MerchantDailyIncomeNotifyScheduler.aggregateIncome(List.of(
                split("M1", "ACCRUED", 500),
                split("M1", "ACCRUED", 260),
                split("M2", "ACCRUED", 0),          // 当日 0.00：不发（拍板）
                split("M3", "LEDGER_ONLY", 100)));
        assertEquals(760L, m.get("M1"));
        assertEquals(100L, m.get("M3"));
        assertFalse(m.containsKey("M2"), "0.00 商户不应出现在发送集合");
    }

    @Test
    void excludesReversedAndBlankMerchants() {
        Map<String, Long> m = MerchantDailyIncomeNotifyScheduler.aggregateIncome(List.of(
                split("M1", "VOIDED", 500),          // 全额退款冲正：不计
                split("M1", "REVERSED", 300),        // 分账回退冲正：不计
                split("", "ACCRUED", 100),           // 空商户：不计
                split(null, "ACCRUED", 100)));
        assertTrue(m.isEmpty());
    }

    @Test
    void negativeMerchantCentsClampedToZero() {
        Map<String, Long> m = MerchantDailyIncomeNotifyScheduler.aggregateIncome(List.of(
                split("M1", "ACCRUED", -50)));
        assertFalse(m.containsKey("M1"), "负数按 0 处理后仍为 0.00：不发");
    }
}
