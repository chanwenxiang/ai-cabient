package com.aicabinet.trade.support;

/**
 * 纠纷工单状态机（S-P2-6）：OPEN → RESOLVED → CLOSED；RESOLVED/CLOSED 可重开回 OPEN。
 * 业务入口须经本类判定，避免散落字符串比较漂移。
 */
public final class DisputeTicketTransitions {

    public static final String OPEN = "OPEN";
    public static final String RESOLVED = "RESOLVED";
    public static final String CLOSED = "CLOSED";

    private DisputeTicketTransitions() {}

    public static String normalize(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        return status.trim().toUpperCase();
    }

    /** 认领 / 结案 / 回复：仅 OPEN */
    public static boolean canActWhileOpen(String status) {
        return OPEN.equals(normalize(status));
    }

    /** 归档关闭：仅 RESOLVED → CLOSED */
    public static boolean canClose(String status) {
        return RESOLVED.equals(normalize(status));
    }

    /** 运营重开：RESOLVED / CLOSED → OPEN */
    public static boolean canReopen(String status) {
        String normalized = normalize(status);
        return RESOLVED.equals(normalized) || CLOSED.equals(normalized);
    }

    /** 消费者事后申诉：已结案工单不可再 file（须走运营重开或退款路径） */
    public static boolean blocksConsumerFile(String status) {
        return canReopen(status);
    }

    /** 全额退款路径：已结案工单可重开承载退款流水 */
    public static boolean canReopenForRefundFlow(String status) {
        return canReopen(status);
    }
}
