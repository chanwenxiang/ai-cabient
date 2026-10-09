package com.aicabinet.trade.support;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 后台设密统一规则（CB-021 采纳项，2026-10-09）。
 *
 * <p>🔴 **为什么原来会错、为什么静默**：改造前运营侧设密只查长度，且同一规则在
 * {@code OpsRbacService} 与 {@code AuthService} 各复制一份「6-64 位」判断；商户团队侧
 * 只查「至少 6 位」，连上限都没有。纯数字 / 纯字母密码可直接设置成功——没有任何
 * 校验拦截，也没有任何提示，弱密码静默落库（竞品对照 docs/COMPETITOR_BENCHMARK.md
 * CB-021：同行后台普遍要求字母+数字混合，故采纳）。</p>
 *
 * <p>只作用于**新设 / 重置 / 修改**路径；登录校验不做复杂度判断——存量弱密码账号
 * 不受影响（不锁存量，由管理员重置 / 用户自改逐步收敛）。</p>
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    /**
     * 校验新密码：6-64 位，且必须同时包含字母与数字。
     * 不合规抛 400（reason 为用户可见中文文案）。
     */
    public static void validate(String password) {
        if (password == null || password.length() < 6 || password.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码长度需在 6-64 位之间");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码须同时包含字母和数字");
        }
    }
}
