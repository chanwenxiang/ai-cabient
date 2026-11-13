package com.aicabinet.trade.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * TimeWindows 单测：窗口语义 + 时区归一 + 非正参数拒绝。
 * dayKey 用跨时区样例钉住「系统默认时区 ≠ 上海时区」的归一行为（UTC 18:00 在沪已是次日 02:00）。
 */
class TimeWindowsTest {

    @Test
    void now_uses_shanghai_zone() {
        assertEquals(LocalDate.now(ZoneId.of("Asia/Shanghai")), TimeWindows.now());
    }

    @Test
    void dayRange_is_closed_interval_inclusive_today() {
        LocalDate today = LocalDate.now(TimeWindows.ZONE);
        LocalDate[] oneDay = TimeWindows.dayRange(1);
        assertEquals(today, oneDay[0]);
        assertEquals(today, oneDay[1]);
        LocalDate[] seven = TimeWindows.dayRange(7);
        assertEquals(today.minusDays(6), seven[0]);
        assertEquals(today, seven[1]);
    }

    @Test
    void dayRange_rejects_non_positive() {
        assertThrows(IllegalArgumentException.class, () -> TimeWindows.dayRange(0));
        assertThrows(IllegalArgumentException.class, () -> TimeWindows.dayRange(-3));
    }

    @Test
    void monthRange_is_closed_interval_of_month_starts() {
        LocalDate today = LocalDate.now(TimeWindows.ZONE);
        LocalDate thisMonthStart = today.withDayOfMonth(1);
        LocalDate[] oneMonth = TimeWindows.monthRange(1);
        assertEquals(thisMonthStart, oneMonth[0]);
        assertEquals(thisMonthStart, oneMonth[1]);
        LocalDate[] three = TimeWindows.monthRange(3);
        assertEquals(thisMonthStart.minusMonths(2), three[0]);
        assertEquals(thisMonthStart, three[1]);
    }

    @Test
    void monthRange_rejects_non_positive() {
        assertThrows(IllegalArgumentException.class, () -> TimeWindows.monthRange(0));
    }

    @Test
    void dayKey_normalizes_to_shanghai_business_day() {
        // UTC 2026-11-11T18:00Z 在上海是 2026-11-12 02:00 —— 业务日必须归到 11-12
        assertEquals("2026-11-12", TimeWindows.dayKey(Instant.parse("2026-11-11T18:00:00Z")));
        // UTC 2026-11-12T00:30Z 在上海是 2026-11-12 08:30 —— 仍归 11-12
        assertEquals("2026-11-12", TimeWindows.dayKey(Instant.parse("2026-11-12T00:30:00Z")));
        // UTC 2026-11-12T17:00Z 在上海是 2026-11-13 01:00 —— 归 11-13
        assertEquals("2026-11-13", TimeWindows.dayKey(Instant.parse("2026-11-12T17:00:00Z")));
    }

    @Test
    void dayKey_rejects_null() {
        assertThrows(NullPointerException.class, () -> TimeWindows.dayKey(null));
        assertNotNull(TimeWindows.sqlDayBucket());
    }

    @Test
    void sqlDayBucket_renders_from_single_source() {
        assertEquals("AT TIME ZONE 'Asia/Shanghai'", TimeWindows.sqlDayBucket());
    }
}
