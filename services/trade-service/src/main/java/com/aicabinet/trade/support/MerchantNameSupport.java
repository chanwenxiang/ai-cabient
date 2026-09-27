package com.aicabinet.trade.support;

/**
 * 商户展示名规范化：库中因编码损坏出现 {@code ????} 时，回退到可读中文名。
 * 源文件请保持 UTF-8，避免再次写入乱码。
 *
 * <p>新商户编号由系统发 12 位数字（见 {@code MerchantIdService}）；历史 {@code MCH-*}
 * 仅作损坏名回退映射，新建禁止再手填。
 */
public final class MerchantNameSupport {

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

    /** 解析可展示的商户名；损坏时用通用兜底（不再依赖固定 MCH-* 主键）。 */
    public static String resolve(String merchantId, String storedName) {
        if (storedName != null && !storedName.isBlank() && !isCorrupted(storedName)) {
            return storedName;
        }
        if (isCorrupted(storedName)) {
            if ("MCH-DEFAULT".equals(merchantId)) {
                return "默认直营商户";
            }
            if ("MCH-EAST".equals(merchantId)) {
                return "华东演示商户";
            }
            if ("MCH-OTHER".equals(merchantId)) {
                return "演示商户B";
            }
            return "演示商户";
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
            return fallbackStoredReason(storedReason);
        }
        return switch (reviewCode.trim().toUpperCase()) {
            case "GRAVITY_FILL" -> "视觉为空，仅有重力信号（非生产识别精度），需人工审核";
            case "GRAVITY_MISMATCH" -> "视觉与重力数量不一致，需人工审核";
            case "MOCK", "FALLBACK" -> "模拟/兜底识别结果，非生产精度，需人工审核";
            case "LOW_CONFIDENCE" -> "识别置信度不足，需人工审核";
            case "EMPTY" -> "未识别到商品，需人工审核";
            case "TIMEOUT" -> "识别超时，已转人工审核，本次暂未扣款";
            default -> fallbackStoredReason(storedReason);
        };
    }

    private static String fallbackStoredReason(String storedReason) {
        if (isCorrupted(storedReason)) {
            return "识别结果需人工审核";
        }
        return storedReason == null ? "" : storedReason;
    }
}
