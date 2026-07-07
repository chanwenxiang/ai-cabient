package com.aicabinet.common.dto;

public record AdminDailyStatDto(
        String date,
        long orderCount,
        long revenueCents
) {}
