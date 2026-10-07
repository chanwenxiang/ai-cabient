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
    Long activityId,
    /** V319 券可用范围类型：ALL / DEVICE / MERCHANT。空白按 ALL（不限制）。 */
    String scopeType,
    /** V319：{@code scopeType=MERCHANT} 时必填。 */
    String scopeMerchantId,
    /** V319：{@code scopeType=DEVICE} 时生效的柜机 ID 集合（空 = 不限制）。 */
    java.util.List<String> scopeDeviceIds
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
                validityDays, maxIssueCount, description, null, null, null, java.util.List.of());
    }

    /**
     * 🔴 V319 补：带活动绑定但不带范围（9 参）。
     *
     * <p>加 record 字段后 canonical 从 9 参变 12 参，所有走 9 参的调用方会集体编译失败
     * ⇒ 兼容构造必须<b>逐档补齐</b>，只补 8 参那档会让 9 参这条路断掉。
     */
    public UpdateCouponRequest(
            String couponName,
            String couponType,
            int denominationCents,
            int minSpendCents,
            Integer discountPercent,
            int validityDays,
            int maxIssueCount,
            String description,
            Long activityId) {
        this(couponName, couponType, denominationCents, minSpendCents, discountPercent,
                validityDays, maxIssueCount, description, activityId, null, null, java.util.List.of());
    }
}
