package com.aicabinet.common.dto;

public record UpdateCouponRequest(
    String couponName,
    String couponType,
    int denominationCents,
    int minSpendCents,
    Integer discountPercent,
    int validityDays,
    int maxIssueCount,
    String description,
    Long activityId
) {
    /** 兼容旧调用方（无活动绑定）。 */
    public UpdateCouponRequest(
            String couponName,
            String couponType,
            int denominationCents,
            int minSpendCents,
            Integer discountPercent,
            int validityDays,
            int maxIssueCount,
            String description) {
        this(couponName, couponType, denominationCents, minSpendCents, discountPercent,
                validityDays, maxIssueCount, description, null);
    }
}
