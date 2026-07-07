package com.aicabinet.common.dto;

public record AdminStatsDto(
        long deviceTotal,
        long deviceOnline,
        long sessionActive,
        long sessionToday,
        long orderToday,
        long revenueTodayCents,
        long orderTotal,
        long revenueTotalCents,
        long disputeOpen
) {}
