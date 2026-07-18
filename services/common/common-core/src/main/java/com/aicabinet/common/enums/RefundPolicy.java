package com.aicabinet.common.enums;

/**
 * 柜机/全局退款策略。
 * <ul>
 *   <li>{@link #AUTO_REFUND} — 消费者可自助立即退款</li>
 *   <li>{@link #DISPUTE_ONLY} — 仅可申诉，由运营审核后退款</li>
 * </ul>
 */
public enum RefundPolicy {
    AUTO_REFUND,
    DISPUTE_ONLY;

    public static RefundPolicy from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase();
        if ("INHERIT".equals(v) || "DEFAULT".equals(v) || "NULL".equals(v)) {
            return null;
        }
        return switch (v) {
            case "AUTO_REFUND", "AUTO", "SELF_SERVICE" -> AUTO_REFUND;
            case "DISPUTE_ONLY", "MANUAL", "REVIEW" -> DISPUTE_ONLY;
            default -> null;
        };
    }

    public static RefundPolicy fromOrDefault(String raw, RefundPolicy fallback) {
        RefundPolicy p = from(raw);
        return p != null ? p : fallback;
    }
}
