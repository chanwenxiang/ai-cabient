package com.aicabinet.common.constants;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * V319 券可用范围类型（E10 / 缺口 #10）。
 *
 * <p>🔴 <b>为什么要有这个枚举</b>：迁移前 {@code coupon_definition.device_scope}
 * 已存在但<b>零消费者</b> —— CouponService 不读它、mapper 不按它筛、库里全是 {@code ALL}。
 * 运营改了它，用户侧毫无变化（典型的「能力已建、链路未通」）。
 * 本枚举 + {@link CouponScopeValidator} 让范围<b>真的参与判定</b>。
 *
 * <p>⚠️ <b>为什么不用 DB CHECK 枚举全部类型</b>：范围类型会随运营需要增长
 * （今天 3 种，明年可能加「指定活动」）。CHECK 每次加类型都要改约束 + 全表校验。
 * ⇒ 类型校验放这里（Java），DB 只做「非空/长度」与设备集合自洽。
 */
public final class CouponScopeType {

    /** 全平台通用。不限制柜机/商户。 */
    public static final String ALL = "ALL";

    /** 指定柜机。生效范围取 {@code scope_device_ids}。 */
    public static final String DEVICE = "DEVICE";

    /** 指定商户。该商户名下所有柜机可用（依据 {@code device_info.merchant_id}）。 */
    public static final String MERCHANT = "MERCHANT";

    /** 允许写入的取值集合。空数组 {@code DEVICE} 合法（=不限制，退化为 ALL）。 */
    public static final Set<String> ALLOWED = Set.of(ALL, DEVICE, MERCHANT);

    private CouponScopeType() {
    }

    /**
     * 归一化运营传入的范围类型。
     *
     * <p>🔴 <b>null / 空白 一律归一为 {@link #ALL}，不返回 null</b>：
     * 存量券的 {@code scope_type} 是 NULL，若把 null 传下去，判定会走「未知类型 ⇒ 放行」
     * 还是「未知类型 ⇒ 拒绝」两条完全相反的路。**归一到 ALL 意味着「不限制」**——
     * 这是与迁移回填（NULL → ALL）一致的语义。
     *
     * @throws IllegalArgumentException 传入非空且非法的值
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED.contains(v)) {
            throw new IllegalArgumentException(
                    "scopeType 必须是 " + String.join("/", sorted()) + " 之一，实际: " + raw);
        }
        return v;
    }

    public static boolean isValid(String raw) {
        if (raw == null || raw.isBlank()) {
            return true; // 空白 =未填= ALL
        }
        return ALLOWED.contains(raw.trim().toUpperCase(Locale.ROOT));
    }

    private static List<String> sorted() {
        return Arrays.asList(ALL, DEVICE, MERCHANT);
    }
}
