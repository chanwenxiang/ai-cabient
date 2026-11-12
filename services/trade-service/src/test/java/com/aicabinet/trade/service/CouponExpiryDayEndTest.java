package com.aicabinet.trade.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3-6（2026-11-12）：券有效期是「发放当日 + N 个自然日，到期当天整天可用」，
 * 不是「发放时刻 + N×24 小时」。
 *
 * <p>修前 {@code uc.setExpireAt(Instant.now().plus(validityDays, DAYS))} 让下午领的券在
 * 到期日同一时刻就失效（比如 20:00 领 1 天券 ⇒ 次日 20:00 过期，用户以为还有一天，
 * 晚上核销被拒）。判据钉住两点：① 过期时刻落在「发放日 + N 日」的 23:59:59.999999999；
 * ② 同一天任何时刻发放都得到**同一个**过期时刻（旧实现在这里必然不同）。
 */
class CouponExpiryDayEndTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate ISSUE_DATE = LocalDate.of(2026, 11, 12);

    private static LocalDateTime atZone(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZONE);
    }

    private static Instant expire(LocalDate date, LocalTime issuedAt, int validityDays) {
        return CouponService.expireAtEndOfDay(LocalDateTime.of(date, issuedAt), validityDays);
    }

    @Test
    void expiresAtEndOfDay_notAtIssueMomentPlus24h() {
        Instant expireAt = expire(ISSUE_DATE, LocalTime.NOON, 1);
        LocalDateTime local = atZone(expireAt);
        assertEquals(LocalDate.of(2026, 11, 13), local.toLocalDate());
        assertEquals(LocalTime.MAX, local.toLocalTime(),
                "P3-6：到期当天必须整天可用，过期点是当日 23:59:59.999999999");
    }

    @Test
    void validityDaysIsCalendarDays_notFixed24hWindows() {
        assertEquals(LocalDate.of(2026, 11, 19), atZone(expire(ISSUE_DATE, LocalTime.NOON, 7)).toLocalDate());
        assertEquals(LocalDate.of(2026, 12, 12), atZone(expire(ISSUE_DATE, LocalTime.NOON, 30)).toLocalDate());
    }

    @Test
    void issueMomentWithinSameDayDoesNotChangeExpiry() {
        // 旧写法在 10:00 与 20:00 发放会得到相差 10 小时的过期时刻；新写法只看发放日。
        Instant morning = expire(ISSUE_DATE, LocalTime.of(10, 0), 1);
        Instant evening = expire(ISSUE_DATE, LocalTime.of(20, 0), 1);
        assertEquals(morning, evening, "P3-6：同一天任何时刻发放，券到期时刻必须一致");
    }

    @Test
    void lateAfternoonIssueStillLeavesTheWholeNextDay() {
        Instant expireAt = expire(ISSUE_DATE, LocalTime.of(20, 0), 1);
        Instant nextDayAfternoon = ISSUE_DATE.plusDays(1).atTime(17, 0).atZone(ZONE).toInstant();
        assertTrue(nextDayAfternoon.isBefore(expireAt),
                "P3-6：1 天券在次日下午 17:00 仍应可用（旧实现 20:00 发放时已过期）");
    }
}
