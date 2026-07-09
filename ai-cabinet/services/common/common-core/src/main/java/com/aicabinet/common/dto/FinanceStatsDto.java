package com.aicabinet.common.dto;

public record FinanceStatsDto(
        long revenueTodayCents,
        long cogsTodayCents,
        long grossMarginTodayCents,
        long writeOffTodayCents,
        long writeOffTodayQty,
        long revenueTotalCents,
        long cogsTotalCents,
        long grossMarginTotalCents
) {}
