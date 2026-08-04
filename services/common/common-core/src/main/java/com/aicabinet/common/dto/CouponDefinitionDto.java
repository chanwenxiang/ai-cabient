package com.aicabinet.common.dto;

public record CouponDefinitionDto(
    Long couponDefId,
    String couponName,
    String couponType,
    int denominationCents,
    int minSpendCents,
    Integer discountPercent,
    int validityDays,
    int maxIssueCount,
    int issuedCount,
    String status,
    String description
) {}
