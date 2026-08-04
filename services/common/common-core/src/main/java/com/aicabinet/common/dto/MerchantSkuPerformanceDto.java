package com.aicabinet.common.dto;

public record MerchantSkuPerformanceDto(
        String skuId,
        String skuName,
        long qtySold,
        long revenueCents,
        long grossMarginCents,
        double grossMarginRate,
        long currentStock,
        double averageDailySales,
        Double daysOfCover,
        String performanceLevel,
        String recommendation
) {}
