package com.aicabinet.trade.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 业务日期窗口单源（S4③，2026-11-12）：所有「今天 / 近 N 天 / 近 N 月」的窗口与
 * SQL 日桶必须走这里，禁止业务代码自行 {@code LocalDate.now()} 拼窗口——时区随 JVM
 * 默认漂移的家族（R19-17 / R21-2 / R22-4）已经付过三次账。
 *
 * <p>时区单一事实源 = {@link ScheduleZones#ZONE}（容器 ENV TZ 与 JVM -Duser.timezone
 * 的双钉由 {@code check-tz-pinned} 门禁守护，见 S4①）；
 * SQL 侧的日桶片段由 {@link #sqlDayBucket()} 渲染，由 {@code check-mapper-tz-timezone}
 * 门禁守护，禁止 mapper XML 裸写字面量。</p>
 *
 * <p>窗口语义：{@link #dayRange}/{@link #monthRange} 返回 <b>闭区间 [from, to]</b>，
 * 含今日/本月；非正参数直接抛——0/负数在窗口语义下是无限/倒挂，正是本族事故形态。</p>
 */
public final class TimeWindows {

    public static final ZoneId ZONE = ScheduleZones.ZONE;

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private TimeWindows() {
    }

    /** 上海时区下的「今天」。存量 {@code LocalDate.now().plusX(...)} 类算术改走本方法即完成时区单源。 */
    public static LocalDate now() {
        return LocalDate.now(ZONE);
    }

    /**
     * 近 {@code daysBack} 天的闭区间（含今日）：[today-(daysBack-1), today]。
     *
     * @throws IllegalArgumentException daysBack ≤ 0
     */
    public static LocalDate[] dayRange(int daysBack) {
        requirePositive(daysBack, "dayRange.daysBack");
        LocalDate to = now();
        return new LocalDate[] { to.minusDays(daysBack - 1L), to };
    }

    /**
     * 近 {@code monthsBack} 个自然月的闭区间（含本月）：[本月1日-(monthsBack-1)个月, 本月1日]。
     *
     * @throws IllegalArgumentException monthsBack ≤ 0
     */
    public static LocalDate[] monthRange(int monthsBack) {
        requirePositive(monthsBack, "monthRange.monthsBack");
        LocalDate monthStart = now().withDayOfMonth(1);
        return new LocalDate[] { monthStart.minusMonths(monthsBack - 1L), monthStart };
    }

    /**
     * 业务日键：Instant 在 {@link ScheduleZones#ZONE} 下的业务日（yyyy-MM-dd）。
     * 跨时区数据（渠道回执、设备上报）落库前必须经此归一，禁止用系统默认时区截日。
     */
    public static String dayKey(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return DAY_FORMAT.format(instant.atZone(ZONE).toLocalDate());
    }

    /**
     * SQL 日桶片段：mapper XML / Java SQL 拼日桶时引用，禁止裸写
     * {@code AT TIME ZONE 'Asia/Shanghai'} 字面量（改动时区要改一处）。
     */
    public static String sqlDayBucket() {
        return "AT TIME ZONE '" + ScheduleZones.ZONE_ID + "'";
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    name + " 必须为正：0/负数在窗口语义下是无限/倒挂，即本族事故形态");
        }
    }
}
