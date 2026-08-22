package com.aicabinet.trade.service;

/**
 * 退款库存策略：对齐无人零售竞品「退货退款 / 仅退款不退货」。
 */
public final class RefundInventoryPolicy {

    private RefundInventoryPolicy() {}

    /**
     * @param explicit          请求显式指定；非 null 时直接采用
     * @param reason            退款/申诉原因
     * @param defaultIfUnknown  无法从文案推断时的默认（运营建议 false；消费者自助误识别场景多为 true）
     */
    public static boolean resolve(Boolean explicit, String reason, boolean defaultIfUnknown) {
        if (explicit != null) {
            return explicit;
        }
        if (reason == null || reason.isBlank()) {
            return defaultIfUnknown;
        }
        String r = reason.trim();
        // 货仍在柜 / 误识别：应回库
        if (containsAny(r, "没拿", "未拿", "没有拿", "空拿", "误识别", "识别有误", "识别错误",
                "多扣", "重复扣", "错扣", "请核对识别")) {
            return true;
        }
        // 货已离柜：仅退款，记损耗口径（不回库）
        if (containsAny(r, "质量", "变质", "临期", "过期", "损坏", "破损", "异物",
                "已拿走", "不退货", "仅退款", "不想退货")) {
            return false;
        }
        return defaultIfUnknown;
    }

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
