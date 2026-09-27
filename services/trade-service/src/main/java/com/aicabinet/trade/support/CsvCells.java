package com.aicabinet.trade.support;

/**
 * CSV 单元格统一转义：分隔符/引号/换行按 RFC 包裹，另做公式注入中和——
 * 以 {@code = + - @ TAB CR} 开头的单元格在 Excel/WPS 中会被当公式执行
 *（OWASP CSV Injection），统一前缀 {@code '} 强制按文本处理。
 */
public final class CsvCells {
    private CsvCells() {}

    public static String escape(String value) {
        if (value == null || "null".equals(value)) {
            return "";
        }
        if (value.matches("^[=+\\-@\t\r].*")) {
            value = "'" + value;
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
