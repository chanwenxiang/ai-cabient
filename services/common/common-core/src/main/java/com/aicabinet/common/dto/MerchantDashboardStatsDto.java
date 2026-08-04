package com.aicabinet.common.dto;

public record MerchantDashboardStatsDto(
        int deviceTotal,
        int deviceOnline,
        int deviceOffline,
        long ordersToday,
        long revenueTodayCents,
        long merchantIncomeTodayCents,
        long merchantIncomeTotalCents,
        long pendingSplitCount,
        long pendingSplitAmountCents,
        long settledMonthCents,
        long failedSplitCount
) {}
