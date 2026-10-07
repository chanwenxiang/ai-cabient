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
    Long activityId,
    /** V319 券可用范围类型：ALL / DEVICE / MERCHANT。 */
    String scopeType,
    /** V319：MERCHANT 范围时为商户 ID。 */
    String scopeMerchantId,
    /** V319：DEVICE 范围时为柜机 ID 集合。 */
    java.util.List<String> scopeDeviceIds
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
                validityDays, maxIssueCount, issuedCount, status, description, null,
                null, null, java.util.List.of());
    }
}
