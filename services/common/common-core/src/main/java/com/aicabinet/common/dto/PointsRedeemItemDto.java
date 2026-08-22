package com.aicabinet.common.dto;

import java.time.Instant;

public record PointsRedeemItemDto(
        Long itemId,
        String title,
        String subtitle,
        String coverEmoji,
        int pointsCost,
        Long couponDefId,
        String couponName,
        int stockTotal,
        int redeemedCount,
        int availableStock,
        int sortOrder,
        String status,
        Instant createdAt,
        Integer denominationCents,
        Integer minSpendCents,
        Integer validityDays,
        String deviceScope
) {
    public PointsRedeemItemDto(
            Long itemId,
            String title,
            String subtitle,
            String coverEmoji,
            int pointsCost,
            Long couponDefId,
            String couponName,
            int stockTotal,
            int redeemedCount,
            int availableStock,
            int sortOrder,
            String status,
            Instant createdAt
    ) {
        this(itemId, title, subtitle, coverEmoji, pointsCost, couponDefId, couponName,
                stockTotal, redeemedCount, availableStock, sortOrder, status, createdAt,
                null, null, null, null);
    }
}
