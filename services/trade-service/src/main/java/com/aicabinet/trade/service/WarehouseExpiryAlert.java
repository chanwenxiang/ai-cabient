package com.aicabinet.trade.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * V320 近效期预警的纯换算（剩余天数 / 紧急度 / daysAhead 夹取）。
 *
 * <p>🔴 <b>为什么要抽成类</b>：这些换算最初写在 Service 的私有方法里，
 * 于是测试只能「在测试里抄一份」—— 而抄一份的测试<b>改实现不会变红</b>，
 * 是假测试。抽成无状态类后**生产代码与测试调同一份**。
 *
 * <p>🔴 <b>为什么手写减法不行</b>：{@code expiry.getDayOfMonth() - today.getDayOfMonth()}
 * 在 10-30 → 11-02 会算出 <b>2</b>（正确 3），因为「日」不是「天」。
 * 跨月、闰年都会错 ⇒ 必须用 {@link ChronoUnit#DAYS}。
 */
public final class WarehouseExpiryAlert {

    /** 提前多少天算「近效期」的默认值。 */
    public static final int DEFAULT_DAYS_AHEAD = 30;

    /** daysAhead 的上限：不封顶的话有人传 99999 天，接口会退化成「列出全部库存」，语义就废了。 */
    public static final int MAX_DAYS_AHEAD = 365;

    private WarehouseExpiryAlert() {
    }

    /**
     * 距到期天数。<b>负数 = 已过期</b>（-3 表示 3 天前过期）—— 这正是运营最需要一眼看到的。
     */
    public static int daysRemaining(LocalDate today, LocalDate expiry) {
        if (today == null || expiry == null) {
            return 0;
        }
        long d = ChronoUnit.DAYS.between(today, expiry);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, d));
    }

    public static boolean isExpired(int daysRemaining) {
        return daysRemaining < 0;
    }

    /**
     * 紧急度分档。
     *
     * <p>🔴 边界：<b>刚好 0 天（今天到期）归 {@code URGENT}</b> ——
     * 今天到期是最紧急的「还能补救」状态，不能落进 {@code SOON}。
     */
    public static String urgency(int daysRemaining) {
        if (daysRemaining < 0) {
            return "EXPIRED";
        }
        if (daysRemaining <= 7) {
            return "URGENT";
        }
        if (daysRemaining <= 30) {
            return "SOON";
        }
        return "NORMAL";
    }

    /** 夹取 daysAhead 到 {@code 1..365}；null 用默认 30。 */
    public static int clampDaysAhead(Integer daysAhead) {
        int days = daysAhead == null ? DEFAULT_DAYS_AHEAD : daysAhead;
        return Math.min(Math.max(days, 1), MAX_DAYS_AHEAD);
    }
}
