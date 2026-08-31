package com.aicabinet.common.dto;

import java.time.Instant;

/** 用户端订单列表摘要 */
public record OrderSummaryDto(
        String orderId,
        String sessionId,
        String deviceId,
        int totalAmountCents,
        String status,
        String payChannel,
        int lineCount,
        String lineSummary,
        int couponDiscountCents,
        Instant createdAt,
        int memberDiscountCents,
        int originalAmountCents,
        Instant refundedAt,
        /** 累计已退款（分） */
        int refundedCents,
        String payTradeNo,
        String paymentOperationId,
        String deviceName,
        String merchantName
) {
    public OrderSummaryDto(
            String orderId,
            String sessionId,
            String deviceId,
            int totalAmountCents,
            String status,
            String payChannel,
            int lineCount,
            String lineSummary,
            int couponDiscountCents,
            Instant createdAt
    ) {
        this(
                orderId,
                sessionId,
                deviceId,
                totalAmountCents,
                status,
                payChannel,
                lineCount,
                lineSummary,
                couponDiscountCents,
                createdAt,
                0,
                totalAmountCents + Math.max(0, couponDiscountCents),
                null,
                0,
                null,
                null,
                null,
                null
        );
    }

    /** 兼容无退款额/支付单号/展示名的旧调用 */
    public OrderSummaryDto(
            String orderId,
            String sessionId,
            String deviceId,
            int totalAmountCents,
            String status,
            String payChannel,
            int lineCount,
            String lineSummary,
            int couponDiscountCents,
            Instant createdAt,
            int memberDiscountCents,
            int originalAmountCents,
            Instant refundedAt
    ) {
        this(
                orderId,
                sessionId,
                deviceId,
                totalAmountCents,
                status,
                payChannel,
                lineCount,
                lineSummary,
                couponDiscountCents,
                createdAt,
                memberDiscountCents,
                originalAmountCents,
                refundedAt,
                0,
                null,
                null,
                null,
                null
        );
    }

    /** 兼容无展示冗余字段的调用 */
    public OrderSummaryDto(
            String orderId,
            String sessionId,
            String deviceId,
            int totalAmountCents,
            String status,
            String payChannel,
            int lineCount,
            String lineSummary,
            int couponDiscountCents,
            Instant createdAt,
            int memberDiscountCents,
            int originalAmountCents,
            Instant refundedAt,
            int refundedCents,
            String payTradeNo,
            String paymentOperationId
    ) {
        this(
                orderId,
                sessionId,
                deviceId,
                totalAmountCents,
                status,
                payChannel,
                lineCount,
                lineSummary,
                couponDiscountCents,
                createdAt,
                memberDiscountCents,
                originalAmountCents,
                refundedAt,
                refundedCents,
                payTradeNo,
                paymentOperationId,
                null,
                null
        );
    }
}
