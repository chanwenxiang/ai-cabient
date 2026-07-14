package com.aicabinet.common.dto;

import java.time.Instant;

public record AdminOrderSummaryDto(
        String orderId,
        String sessionId,
        Long userId,
        String deviceId,
        int totalAmountCents,
        String status,
        int lineCount,
        Instant createdAt
) {}
