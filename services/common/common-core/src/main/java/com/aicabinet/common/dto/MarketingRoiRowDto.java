package com.aicabinet.common.dto;

public record MarketingRoiRowDto(
        Long activityId,
        String activityName,
        String activityType,
        String status,
        long budgetCents,
        long usedCents,
        long claimedCount,
        long usedCount,
        long orderCount,
        long orderRevenueCents,
        long discountCents,
        double redeemRate
) {}
