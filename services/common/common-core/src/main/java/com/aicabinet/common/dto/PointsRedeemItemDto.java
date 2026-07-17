package com.aicabinet.common.dto;

public record PointsRedeemItemDto(
        Long itemId,
        String title,
        String subtitle,
        String coverEmoji,
        int pointsCost,
        Long couponDefId,
        String couponName,
        int denominationCents,
        int minSpendCents,
        String couponType,
        int stockLeft,
        boolean canRedeem
) {}
