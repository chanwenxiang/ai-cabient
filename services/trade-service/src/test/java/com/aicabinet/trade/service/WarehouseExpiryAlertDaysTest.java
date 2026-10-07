package com.aicabinet.trade.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V320 仓库近效期预警：剩余天数 / 紧急度 / daysAhead 夹取。
 *
 * <p>🔴 <b>调生产类 {@link WarehouseExpiryAlert} 本身</b>，不在本测试里抄一份换算 ——
 * 抄一份的测试<b>永远绿、改实现不会变红</b>（本日第2 次踩这个坑，第一次是 V314损耗算术）。
 * 识别信号：测试文件里出现自己写的同名影子方法。
 *
 * <p>本类守的是「**跨月 / 闰年不出错**」：手写
 * {@code expiry.getDayOfMonth() - today.getDayOfMonth()} 在
 * 10-30 → 11-02 会算出 2（正确 3），因为「日」不是「天」。
 */
class WarehouseExpiryAlertDaysTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);

    @Test
    @DisplayName("同一天 = 0")
    void sameDay_isZero() {
        assertEquals(0, WarehouseExpiryAlert.daysRemaining(TODAY, TODAY));
    }

    @Test
    @DisplayName("🔴 跨月：10-30 → 11-02 应为 3 天（手写减法会算成 2）")
    void crossMonth_isCorrect() {
        assertEquals(3, WarehouseExpiryAlert.daysRemaining(
                LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2)));
    }

    @Test
    @DisplayName("🔴 闰年 2028-02-28 → 03-01 应为 2 天")
    void leapYear_isCorrect() {
        assertEquals(2, WarehouseExpiryAlert.daysRemaining(
                LocalDate.of(2028, 2, 28), LocalDate.of(2028, 3, 1)));
    }

    @Test
    @DisplayName("非闰年 2027-02-28 → 03-01 应为 1 天")
    void nonLeapYear_isCorrect() {
        assertEquals(1, WarehouseExpiryAlert.daysRemaining(
                LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 1)));
    }

    @Test
    @DisplayName("过期为负：-3 表示 3 天前过期（运营最需要一眼看到这个）")
    void expired_isNegative() {
        assertEquals(-3, WarehouseExpiryAlert.daysRemaining(
                TODAY, LocalDate.of(2026, 10, 4)));
        assertTrue(WarehouseExpiryAlert.isExpired(
                WarehouseExpiryAlert.daysRemaining(TODAY, LocalDate.of(2026, 10, 1))));
    }

    @Test
    @DisplayName("未过期不算expired")
    void notExpired_isFalse() {
        assertFalse(WarehouseExpiryAlert.isExpired(
                WarehouseExpiryAlert.daysRemaining(TODAY, LocalDate.of(2026, 10, 7))));
        assertFalse(WarehouseExpiryAlert.isExpired(
                WarehouseExpiryAlert.daysRemaining(TODAY, LocalDate.of(2026, 12, 1))));
    }

    @Test
    @DisplayName("一年365 天；**真正跨闰日**的区间（2028-01-01 → 2029-01-01）才是 366")
    void oneYear_takesLeapIntoAccount() {
        assertEquals(365, WarehouseExpiryAlert.daysRemaining(
                LocalDate.of(2026, 10, 7), LocalDate.of(2027, 10, 7)));
        // 🔴 2028-02-29 落在「2028-10-07 → 2029-10-07」区间**之外** ⇒仍是 365。
        //   换成年初起点才真正跨过闰日 ⇒ 366。
        //   （我一开始按366 写期望，测试红了 —— 是期望错，不是实现错。）
        assertEquals(365, WarehouseExpiryAlert.daysRemaining(
                LocalDate.of(2028, 10, 7), LocalDate.of(2029, 10, 7)));
        assertEquals(366, WarehouseExpiryAlert.daysRemaining(
                LocalDate.of(2028, 1, 1), LocalDate.of(2029, 1, 1)));
    }

    @Test
    @DisplayName("紧急度分档：EXPIRED / URGENT(≤7) / SOON(≤30) / NORMAL")
    void urgency_buckets() {
        assertEquals("EXPIRED", WarehouseExpiryAlert.urgency(-1));
        assertEquals("EXPIRED", WarehouseExpiryAlert.urgency(-30));
        assertEquals("URGENT", WarehouseExpiryAlert.urgency(1));
        assertEquals("URGENT", WarehouseExpiryAlert.urgency(7));
        assertEquals("SOON", WarehouseExpiryAlert.urgency(8));
        assertEquals("SOON", WarehouseExpiryAlert.urgency(30));
        assertEquals("NORMAL", WarehouseExpiryAlert.urgency(31));
    }

    @Test
    @DisplayName("🔴 边界：刚好 0 天（今天到期）应是 URGENT，不是 SOON")
    void boundary_zeroDays_isUrgent() {
        assertEquals("URGENT", WarehouseExpiryAlert.urgency(0));
    }

    @Test
    @DisplayName("daysAhead 夹取在 1..365；null 用默认 30")
    void daysAhead_isClamped() {
        assertEquals(30, WarehouseExpiryAlert.clampDaysAhead(null));
        assertEquals(1, WarehouseExpiryAlert.clampDaysAhead(0));
        assertEquals(1, WarehouseExpiryAlert.clampDaysAhead(-5));
        assertEquals(365, WarehouseExpiryAlert.clampDaysAhead(99999));
        assertEquals(45, WarehouseExpiryAlert.clampDaysAhead(45));
    }

    @Test
    @DisplayName("null 输入不炸（返回 0 / NORMAL）")
    void nullInputs_areSafe() {
        assertEquals(0, WarehouseExpiryAlert.daysRemaining(TODAY, null));
        assertEquals(0, WarehouseExpiryAlert.daysRemaining(null, TODAY));
    }
}
