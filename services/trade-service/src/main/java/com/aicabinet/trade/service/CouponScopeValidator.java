package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CouponScopeType;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * V319 券可用范围判定（E10 / 缺口 #10）。
 *
 * <p>🔴 <b>这个类存在的意义</b>：迁移前 {@code device_scope} 是<b>死字段</b> ——
 * CouponService 不读、mapper 不筛，运营改了用户侧毫无变化。
 * 本类把范围变成<b>可执行的判定</b>，并在唯一拦截点
 * （{@code CouponService.evaluateCoupon}）被调用。
 *
 * <p>⚠️ <b>刻意做成无状态静态类</b>，与生产代码同一份：
 * 测试若自己抄一份判定逻辑，改实现就不会变红 —— 那是假测试。
 */
public final class CouponScopeValidator {

    private CouponScopeValidator() {
    }

    /**
     * 判断某张券在「当前这台柜机」上是否可用。
     *
     * @param scopeType       范围类型（null/空白按 ALL 处理）
     * @param scopeDeviceIds  {@code scope_type=DEVICE} 时的柜机集合（null/空 = 不限制）
     * @param scopeMerchantId {@code scope_type=MERCHANT} 时的商户 ID
     * @param currentDeviceId 当前操作的柜机 ID（null = 场景不涉及柜机，如后台预览）
     * @param deviceMerchantId 当前柜机所属商户（null = 查不到）
     * @return true = 该券在此柜机可用
     */
    public static boolean allowsDevice(
            String scopeType,
            String[] scopeDeviceIds,
            String scopeMerchantId,
            String currentDeviceId,
            String deviceMerchantId) {

        String type = normalizeQuietly(scopeType);

        if (CouponScopeType.DEVICE.equals(type)) {
            Set<String> allowed = normalizeIds(scopeDeviceIds);
            // 🔴 空集合 = 「没配任何柜机」。此时**不限制**（退化为 ALL）而不是「哪儿都不能用」——
            // 反过来会让「运营建了券但还没选柜机」变成「这张券永远用不了」，
            // 而失败原因在界面上完全看不出来。
            if (allowed.isEmpty()) {
                return true;
            }
            if (currentDeviceId == null || currentDeviceId.isBlank()) {
                // 无柜机上下文（后台预览/活动发券）⇒ 无法判定 ⇒ 放行。
                // 理由：发券是运营的主动行为，在预览里就拒会让人以为券坏了。
                return true;
            }
            return allowed.contains(currentDeviceId.trim().toUpperCase(Locale.ROOT));
        }

        if (CouponScopeType.MERCHANT.equals(type)) {
            if (scopeMerchantId == null || scopeMerchantId.isBlank()) {
                // 🔴 声明了 MERCHANT 却没填商户 = 配置不完整。
                // 这里**放行**而不是拒绝：拒绝会让这张券彻底不可用且无提示；
                // 放行则与「未配置范围」的默认语义一致（不限制）。
                // 真正的治理靠运营台提示「MERCHANT 必须选商户」。
                return true;
            }
            if (deviceMerchantId == null || currentDeviceId == null) {
                return true; // 同上：无法判定 ⇒ 不阻断
            }
            return scopeMerchantId.trim().equalsIgnoreCase(deviceMerchantId.trim());
        }

        // ALL（含null/空白/未知值）
        return true;
    }

    /**
     * 校验「范围配置是否自洽」——在<b>建券/改券时</b>调用（fail fast，给运营可读消息）。
     *
     * <p>🔴 与 {@link #allowsDevice} 的宽松不同：<b>写入时必须严格</b>。
     * 判定时宽松是为了不让既有流程突然失败；写入时严格是为了不让错误配置溜进来。
     *
     * @return null 表示合法；否则返回可读的错误原因
     */
    public static String validate(String scopeType,
                                  String[] scopeDeviceIds,
                                  String scopeMerchantId) {
        if (!CouponScopeType.isValid(scopeType)) {
            return "可用范围类型非法";
        }
        String type = normalizeQuietly(scopeType);
        if (CouponScopeType.DEVICE.equals(type)) {
            Set<String> ids = normalizeIds(scopeDeviceIds);
            for (String id : ids) {
                if (id.length() > 64) {
                    return "柜机 ID 长度超限：" + id;
                }
            }
            // 空集合在判定侧等价于 ALL，**允许**（合法的过渡态）
        }
        if (CouponScopeType.MERCHANT.equals(type)) {
            if (scopeMerchantId == null || scopeMerchantId.isBlank()) {
                return "范围类型选了「指定商户」，但没有选择商户";
            }
        }
        return null;
    }

    private static String normalizeQuietly(String raw) {
        if (raw == null || raw.isBlank()) {
            return CouponScopeType.ALL;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        return CouponScopeType.ALLOWED.contains(v) ? v : CouponScopeType.ALL;
    }

    private static Set<String> normalizeIds(String[] ids) {
        if (ids == null || ids.length == 0) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String id : Arrays.asList(ids)) {
            if (id != null && !id.isBlank()) {
                out.add(id.trim().toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }
}
