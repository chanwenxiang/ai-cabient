package com.aicabinet.common.dto;

import java.time.Instant;

/** 运营后台订单列表摘要。 */
public record AdminOrderSummaryDto(
        String orderId,
        String sessionId,
        Long userId,
        String deviceId,
        int totalAmountCents,
        String status,
        String payChannel,
        int lineCount,
        String lineSummary,
        String payTradeNo,
        String paymentOperationId,
        Instant refundedAt,
        int couponDiscountCents,
        boolean inventoryDeducted,
        Instant createdAt
) {}
