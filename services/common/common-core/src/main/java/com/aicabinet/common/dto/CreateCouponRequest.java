package com.aicabinet.common.dto;

public record CreateCouponRequest(
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
    /** V319：{@code scopeType=MERCHANT} 时必填（该商户名下所有柜机可用）。 */
    String scopeMerchantId,
    /** V319：{@code scopeType=DEVICE} 时生效的柜机 ID 集合（空 = 不限制）。 */
    java.util.List<String> scopeDeviceIds
) {
    /** 兼容旧调用方（无活动绑定）。 */
    public CreateCouponRequest(
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
     * <p><b>为什么不省</b>：加 record 字段后 canonical 构造器从 9 参变 12 参，
     * 所有走 9 参的调用方（测试、其它服务）会集体编译失败。
     * 只补8 参兼容构造会让 9 参这条路断掉 ⇒ 必须逐档补齐。
     */
    public CreateCouponRequest(
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
