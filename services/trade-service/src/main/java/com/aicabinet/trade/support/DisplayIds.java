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

    public static String merchantReplenApprovalTitle(long requestId, String deviceName, String merchantName) {
        String cabinet = blankTo(deviceName, "未命名柜机");
        String merchant = blankTo(merchantName, "未知商户");
        return "商户要货 · " + cabinet + " · " + merchant + "（申请 " + requestId + "）";
    }

    public static String merchantReplenRouteName(long requestId, String deviceName) {
        return "商户要货 · " + blankTo(deviceName, "未命名柜机") + "（申请 " + requestId + "）";
    }

    private static String blankTo(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
