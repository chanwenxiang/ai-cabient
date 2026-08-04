package com.aicabinet.trade.service;

import java.util.Locale;
import java.util.Set;

/**
 * 商户功能包常量与权限码映射。
 * 平台开包 ∧ 用户 RBAC → 商户端可见/可调。
 * 前缀表与前端 packages/shared-rbac merchantPackForPerm 保持一致，改时请同步。
 */
public final class MerchantFeaturePacks {

    public static final String FIELD = "field";
    public static final String BIZ = "biz";
    public static final String TEAM = "team";

    private static final Set<String> FIELD_PREFIXES = Set.of(
            "merchant:devices:",
            "merchant:slots:",
            "merchant:temp:",
            "merchant:replenishment:",
            "merchant:alerts:",
            "merchant:inventory:"
    );

    private static final Set<String> BIZ_PREFIXES = Set.of(
            "merchant:orders:",
            "merchant:splits:",
            "merchant:settlements:",
            "merchant:pricing:",
            "merchant:disputes:",
            "merchant:reports:",
            "merchant:trend:",
            "merchant:analytics:",
            "merchant:coupon:",
            "merchant:line-wallet:",
            "merchant:wallet:"
    );

    private static final Set<String> TEAM_PREFIXES = Set.of(
            "merchant:profile:",
            "merchant:users:"
    );

    private MerchantFeaturePacks() {}

    /** 门户准入与目录节点：不绑功能包（始终随 RBAC）。 */
    public static boolean isPackAgnostic(String permCode) {
        if (permCode == null || permCode.isBlank()) {
            return true;
        }
        String code = permCode.trim();
        return "merchant".equals(code)
                || "merchant:portal:access".equals(code)
                || code.startsWith("merchant:nav:");
    }

    /** @return field / biz / team，或 null（不识别 / 包无关） */
    public static String packForPerm(String permCode) {
        if (permCode == null || permCode.isBlank() || isPackAgnostic(permCode)) {
            return null;
        }
        String code = permCode.trim().toLowerCase(Locale.ROOT);
        if (matches(code, FIELD_PREFIXES)) {
            return FIELD;
        }
        if (matches(code, BIZ_PREFIXES)) {
            return BIZ;
        }
        if (matches(code, TEAM_PREFIXES)) {
            return TEAM;
        }
        // 未知 merchant:* 默认不拦截，避免误伤扩展权限
        return null;
    }

    private static boolean matches(String code, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (code.startsWith(prefix) || code.equals(prefix.substring(0, prefix.length() - 1))) {
                return true;
            }
        }
        return false;
    }
}
