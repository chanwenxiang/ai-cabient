package com.aicabinet.common.dto;

import java.time.Instant;

public record SkuDelistReviewDto(
        Long id,
        String skuId,
        String skuName,
        String category,
        String reviewStatus,
        String performanceLevel,
        int salesQty,
        long revenueCents,
        Integer stockDays,
        String actionType,
        String reason,
        String replaceSkuId,
        String replaceSkuName,
        Long reviewedBy,
        Instant reviewedAt,
        Instant createdAt,
        Instant updatedAt
) {}
