package com.aicabinet.common.dto;

public record CreateCouponRequest(
    String couponName,
    String couponType,
    int denominationCents,
    int minSpendCents,
    Integer discountPercent,
    int validityDays,
    int maxIssueCount,
    String description
) {}
