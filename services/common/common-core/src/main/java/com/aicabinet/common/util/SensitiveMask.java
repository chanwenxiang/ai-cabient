package com.aicabinet.common.util;

/**
 * 敏感字段脱敏（身份证、银行卡等），与 {@link PhoneMask} 互补。
 * 日志与对外响应统一走此工具，新增敏感字段默认应脱敏。
 */
public final class SensitiveMask {

    private SensitiveMask() {
    }

    /**
     * 身份证：保留前 3 后 4，中间 *。
     * 例：110101199001011234 → 110***********1234
     */
    public static String idCard(String idCard) {
        if (idCard == null || idCard.isBlank()) {
            return idCard;
        }
        String normalized = idCard.trim();
        if (normalized.length() < 8) {
            return "***";
        }
        int stars = normalized.length() - 7;
        return normalized.substring(0, 3) + "*".repeat(Math.max(stars, 4))
                + normalized.substring(normalized.length() - 4);
    }

    /**
     * 银行卡：保留后 4 位。
     * 例：6222021234567890 → ****7890
     */
    public static String bankCard(String bankCard) {
        if (bankCard == null || bankCard.isBlank()) {
            return bankCard;
        }
        String normalized = bankCard.replaceAll("\\s+", "");
        if (normalized.length() < 4) {
            return "****";
        }
        return "****" + normalized.substring(normalized.length() - 4);
    }
}
