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
        Instant createdAt
) {}
