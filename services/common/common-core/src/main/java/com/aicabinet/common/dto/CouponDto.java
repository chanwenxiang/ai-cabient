package com.aicabinet.common.dto;

import java.time.Instant;

public record CouponDto(
    Long couponId,
    String couponName,
    String couponType,
    int denominationCents,
    int minSpendCents,
    String status,
    Instant expireAt,
    Instant receivedAt,
    Instant usedAt,
    String couponCode,
    String deviceScope,
    String description
) {
    public CouponDto(
            Long couponId,
            String couponName,
            String couponType,
            int denominationCents,
            int minSpendCents,
            String status,
            Instant expireAt,
            Instant receivedAt,
            Instant usedAt,
            String couponCode
    ) {
        this(couponId, couponName, couponType, denominationCents, minSpendCents, status,
                expireAt, receivedAt, usedAt, couponCode, null, null);
    }
}
