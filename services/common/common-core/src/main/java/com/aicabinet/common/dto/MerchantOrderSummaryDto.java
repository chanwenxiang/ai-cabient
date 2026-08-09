package com.aicabinet.common.dto;

import java.time.Instant;

public record MerchantOrderSummaryDto(
        String orderId,
        String sessionId,
        String deviceId,
        int totalAmountCents,
        String status,
        int lineCount,
        Instant createdAt,
        String lineSummary,
        String payChannel,
        int couponDiscountCents
) {}
