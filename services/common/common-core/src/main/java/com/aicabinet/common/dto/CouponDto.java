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
    String couponCode
) {}
