package com.aicabinet.common.dto;

public record MerchantDailyTrendDto(
        String date,
        long orderCount,
        long revenueCents,
        long merchantIncomeCents
) {}
