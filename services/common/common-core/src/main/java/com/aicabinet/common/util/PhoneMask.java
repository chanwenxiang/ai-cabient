package com.aicabinet.common.util;

/**
 * 手机号脱敏：日志与管理端列表响应统一使用，避免明文 PII 外泄。
 * 身份证/银行卡见 {@link SensitiveMask}。
 */
public final class PhoneMask {

    private PhoneMask() {
    }

    /** 例：13900000001 → 139****0001；过短返回 ***。 */
    public static String mask(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        String normalized = phone.trim();
        if (normalized.length() < 7) {
            return "***";
        }
        return normalized.substring(0, 3) + "****" + normalized.substring(normalized.length() - 4);
    }
}
