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
    String description,
    Long activityId
) {
    /** 兼容旧调用方。 */
    public CouponDefinitionDto(
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
            String description) {
        this(couponDefId, couponName, couponType, denominationCents, minSpendCents, discountPercent,
                validityDays, maxIssueCount, issuedCount, status, description, null);
    }
}
