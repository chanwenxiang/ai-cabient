package com.aicabinet.common.dto;

import java.time.Instant;

public record RechargeOrderDto(
        String orderId,
        Long userId,
        int amountCents,
        String channel,
        String status,
        String wxPrepayId,
        String wxTransactionId,
        String alipayTradeNo,
        Instant createdAt,
        Instant paidAt,
        Instant refundedAt
) {}
