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
        int refundedCents,
        String deviceName,
        String merchantName,
        String payTradeNo,
        String paymentOperationId,
        /** 分账状态；无分账为 null */
        String splitStatus
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
                totalAmountCents + Math.max(0, couponDiscountCents), null, 0, null, null, null, null, null);
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
                originalAmountCents, refundedAt, 0, null, null, null, null, null);
    }

    /** 兼容无支付流水/分账字段的调用 */
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
            Instant refundedAt,
            int refundedCents,
            String deviceName,
            String merchantName
    ) {
        this(orderId, sessionId, deviceId, totalAmountCents, status, lineCount, createdAt,
                lineSummary, payChannel, couponDiscountCents, memberDiscountCents,
                originalAmountCents, refundedAt, refundedCents, deviceName, merchantName, null, null, null);
    }

    /** 兼容无分账字段的调用 */
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
            Instant refundedAt,
            int refundedCents,
            String deviceName,
            String merchantName,
            String payTradeNo,
            String paymentOperationId
    ) {
        this(orderId, sessionId, deviceId, totalAmountCents, status, lineCount, createdAt,
                lineSummary, payChannel, couponDiscountCents, memberDiscountCents,
                originalAmountCents, refundedAt, refundedCents, deviceName, merchantName,
                payTradeNo, paymentOperationId, null);
    }
}
