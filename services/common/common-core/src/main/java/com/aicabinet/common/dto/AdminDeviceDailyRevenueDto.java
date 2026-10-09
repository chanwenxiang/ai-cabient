package com.aicabinet.common.dto;

/**
 * CB-018①：柜机×日营收序列单行（设备报表「近 N 日营收趋势」数据源）。
 * 口径与设备报表累计/今日营收一致（SUM(total_amount_cents) 不过滤状态），日序列求和可与累计对平。
 */
public record AdminDeviceDailyRevenueDto(
        String deviceId,
        /** Asia/Shanghai 日历日（yyyy-MM-dd）。 */
        String date,
        long revenueCents,
        long orderCount
) {}
