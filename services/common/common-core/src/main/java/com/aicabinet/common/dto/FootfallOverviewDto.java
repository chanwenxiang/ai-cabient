package com.aicabinet.common.dto;

public record FootfallOverviewDto(
        long totalOpens,
        long totalPaidOrders,
        long revenueCents,
        double conversionRate,
        long avgOrderValueCents,
        long repeatBuyers,
        long deviceCount
) {}
