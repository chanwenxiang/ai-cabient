package com.aicabinet.common.dto;

/** 订单退款结果。 */
public record OrderRefundResultDto(
        String orderId,
        String sessionId,
        String ticketId,
        String status,
        int refundedCents,
        String payChannel,
        String message,
        boolean inventoryRestored,
        /** 是否按行部分退（相对全额） */
        boolean partial
) {
    public OrderRefundResultDto(String orderId, String sessionId, String ticketId,
                                String status, int refundedCents, String payChannel, String message) {
        this(orderId, sessionId, ticketId, status, refundedCents, payChannel, message, false, false);
    }

    public OrderRefundResultDto(String orderId, String sessionId, String ticketId,
                                String status, int refundedCents, String payChannel, String message,
                                boolean inventoryRestored) {
        this(orderId, sessionId, ticketId, status, refundedCents, payChannel, message, inventoryRestored, false);
    }
}
