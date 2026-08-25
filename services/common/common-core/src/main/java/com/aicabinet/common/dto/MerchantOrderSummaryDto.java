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
        int couponDiscountCents,
        int memberDiscountCents,
        int originalAmountCents,
        Instant refundedAt,
        /** 累计已退款（分） */
        int refundedCents
) {
    public MerchantOrderSummaryDto(
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
    ) {
        this(orderId, sessionId, deviceId, totalAmountCents, status, lineCount, createdAt,
                lineSummary, payChannel, couponDiscountCents, 0,
                totalAmountCents + Math.max(0, couponDiscountCents), null, 0);
    }

    public MerchantOrderSummaryDto(
            String orderId,
            String sessionId,
            String deviceId,
            int totalAmountCents,
            String status,
            int lineCount,
            Instant createdAt,
            String lineSummary,
            String payChannel,
            int couponDiscountCents,
            int memberDiscountCents,
            int originalAmountCents,
            Instant refundedAt
    ) {
        this(orderId, sessionId, deviceId, totalAmountCents, status, lineCount, createdAt,
                lineSummary, payChannel, couponDiscountCents, memberDiscountCents,
                originalAmountCents, refundedAt, 0);
    }
}
