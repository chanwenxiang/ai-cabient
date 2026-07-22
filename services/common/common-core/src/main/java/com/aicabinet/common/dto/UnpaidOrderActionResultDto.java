package com.aicabinet.common.dto;

/** 待支付催付 / 关单 / 补扣结果。 */
public record UnpaidOrderActionResultDto(
        String orderId,
        String status,
        String message,
        boolean notified,
        boolean blacklisted
) {
    public UnpaidOrderActionResultDto(String orderId, String status, String message) {
        this(orderId, status, message, false, false);
    }
}
