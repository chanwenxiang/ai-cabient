package com.aicabinet.trade.support;

import java.util.Map;

/**
 * 商户展示名规范化：库中因编码损坏出现 {@code ????} 时，回退到已知中文名。
 * 源文件请保持 UTF-8，避免再次写入乱码。
 */
public final class MerchantNameSupport {

    private static final Map<String, String> KNOWN_NAMES = Map.of(
            "MCH-DEFAULT", "默认直营商户",
            "MCH-EAST", "华东演示商户",
            "MCH-OTHER", "演示商户B"
    );

    private MerchantNameSupport() {
    }

    /** 是否为编码损坏名（纯问号或大量 {@code ?}）。 */
    public static boolean isCorrupted(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (name.matches("^\\?+$")) {
            return true;
        }
        return name.contains("???") || name.chars().filter(ch -> ch == '?').count() >= 2;
    }

    /** 解析可展示的商户名；损坏时优先用已知映射。 */
    public static String resolve(String merchantId, String storedName) {
        if (storedName != null && !storedName.isBlank() && !isCorrupted(storedName)) {
            return storedName;
        }
        String known = KNOWN_NAMES.get(merchantId);
        if (known != null) {
            return known;
        }
        if (storedName != null && !storedName.isBlank() && !isCorrupted(storedName)) {
            return storedName;
        }
        if (merchantId != null && !merchantId.isBlank()) {
            return "演示商户-" + merchantId;
        }
        return "未命名商户";
    }

    /**
     * 争议原因展示：库内 reason 乱码时，按 reviewCode 回退到标准中文说明。
     */
    public static String disputeReason(String reviewCode, String storedReason) {
        if (storedReason != null && !storedReason.isBlank() && !isCorrupted(storedReason)) {
            return storedReason;
        }
        if (reviewCode == null || reviewCode.isBlank()) {
            return isCorrupted(storedReason) ? "识别结果需人工审核" : (storedReason == null ? "" : storedReason);
        }
        return switch (reviewCode.trim().toUpperCase()) {
            case "GRAVITY_FILL" -> "视觉为空，仅有重力信号（非生产识别精度），需人工审核";
            case "GRAVITY_MISMATCH" -> "视觉与重力数量不一致，需人工审核";
            case "MOCK", "FALLBACK" -> "模拟/兜底识别结果，非生产精度，需人工审核";
            case "LOW_CONFIDENCE" -> "识别置信度不足，需人工审核";
            case "EMPTY" -> "未识别到商品，需人工审核";
            case "TIMEOUT" -> "识别超时，已转人工审核，本次暂未扣款";
            default -> isCorrupted(storedReason) ? "识别结果需人工审核" : (storedReason == null ? "" : storedReason);
        };
    }
}
