package com.aicabinet.common.dto;

/** 订单退款结果。 */
public record OrderRefundResultDto(
        String orderId,
        String sessionId,
        String ticketId,
        String status,
        int refundedCents,
        String payChannel,
        String message
) {}
