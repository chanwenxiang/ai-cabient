package com.aicabinet.common.dto;

import java.util.List;

public record UserBehaviorSummaryDto(
        int activeUsers7d,
        int activeUsers30d,
        int newUsers7d,
        int newUsers30d,
        int repeatBuyer7d,
        double repeatPurchaseRate7d,
        int dormantUsers30d,
        int totalUsers,
        long totalOrders,
        long totalRevenueCents,
        double avgOrderValueCents,
        List<UserBehaviorRowDto> topRepeatBuyers,
        List<UserBehaviorRowDto> dormantUsers
) {}
