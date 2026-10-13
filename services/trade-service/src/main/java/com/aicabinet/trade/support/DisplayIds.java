package com.aicabinet.trade.support;

/**
 * 用户可见单号/审批标题：禁止「#3」这类井号前缀，审批主题用人名可读字段。
 */
public final class DisplayIds {

    private DisplayIds() {}

    public static String stripHashIdMarkers(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text.trim();
        }
        return text.replaceAll("#\\{\\w+}", "")
                .replaceAll("#(?=[\\w.-])", "")
                .replaceAll("[ \\t]{2,}", " ")
                .trim();
    }

    /** 补货员自主发起补货的任务路线名（无审批，直接从分仓出库）。 */
    public static String selfReplenRouteName(java.time.LocalDate plannedDate, String deviceName) {
        return "自主补货 · " + blankTo(deviceName, "未命名柜机") + "（" + plannedDate + "）";
    }

    private static String blankTo(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
