package com.aicabinet.common.dto;

import java.time.Instant;

/** 运营后台订单列表摘要。 */
public record AdminOrderSummaryDto(
        String orderId,
        String sessionId,
        Long userId,
        String deviceId,
        String merchantId,
        int totalAmountCents,
        int originalAmountCents,
        int couponDiscountCents,
        int memberDiscountCents,
        String status,
        String payChannel,
        int lineCount,
        String lineSummary,
        String payTradeNo,
        String paymentOperationId,
        Instant refundedAt,
        boolean inventoryDeducted,
        String refundPolicy,
        Instant createdAt,
        String deviceName,
        String merchantName,
        /** 累计已退款（分） */
        int refundedCents,
        /** 支付完成时间；未支付为 null */
        Instant paidAt,
        /** 分账状态（order_revenue_split.status）；无分账为 null */
        String splitStatus
) {
    /** 兼容无退款额/支付时间/分账字段的调用。 */
    public AdminOrderSummaryDto(
            String orderId,
            String sessionId,
            Long userId,
            String deviceId,
            String merchantId,
            int totalAmountCents,
            int originalAmountCents,
            int couponDiscountCents,
            int memberDiscountCents,
            String status,
            String payChannel,
            int lineCount,
            String lineSummary,
            String payTradeNo,
            String paymentOperationId,
            Instant refundedAt,
            boolean inventoryDeducted,
            String refundPolicy,
            Instant createdAt,
            String deviceName,
            String merchantName
    ) {
        this(orderId, sessionId, userId, deviceId, merchantId, totalAmountCents, originalAmountCents,
                couponDiscountCents, memberDiscountCents, status, payChannel, lineCount, lineSummary,
                payTradeNo, paymentOperationId, refundedAt, inventoryDeducted, refundPolicy, createdAt,
                deviceName, merchantName, 0, null, null);
    }

    /** 兼容有退款额、无支付时间/分账。 */
    public AdminOrderSummaryDto(
            String orderId,
            String sessionId,
            Long userId,
            String deviceId,
            String merchantId,
            int totalAmountCents,
            int originalAmountCents,
            int couponDiscountCents,
            int memberDiscountCents,
            String status,
            String payChannel,
            int lineCount,
            String lineSummary,
            String payTradeNo,
            String paymentOperationId,
            Instant refundedAt,
            boolean inventoryDeducted,
            String refundPolicy,
            Instant createdAt,
            String deviceName,
            String merchantName,
            int refundedCents
    ) {
        this(orderId, sessionId, userId, deviceId, merchantId, totalAmountCents, originalAmountCents,
                couponDiscountCents, memberDiscountCents, status, payChannel, lineCount, lineSummary,
                payTradeNo, paymentOperationId, refundedAt, inventoryDeducted, refundPolicy, createdAt,
                deviceName, merchantName, refundedCents, null, null);
    }

    /** 兼容有支付时间、无分账。 */
    public AdminOrderSummaryDto(
            String orderId,
            String sessionId,
            Long userId,
            String deviceId,
            String merchantId,
            int totalAmountCents,
            int originalAmountCents,
            int couponDiscountCents,
            int memberDiscountCents,
            String status,
            String payChannel,
            int lineCount,
            String lineSummary,
            String payTradeNo,
            String paymentOperationId,
            Instant refundedAt,
            boolean inventoryDeducted,
            String refundPolicy,
            Instant createdAt,
            String deviceName,
            String merchantName,
            int refundedCents,
            Instant paidAt
    ) {
        this(orderId, sessionId, userId, deviceId, merchantId, totalAmountCents, originalAmountCents,
                couponDiscountCents, memberDiscountCents, status, payChannel, lineCount, lineSummary,
                payTradeNo, paymentOperationId, refundedAt, inventoryDeducted, refundPolicy, createdAt,
                deviceName, merchantName, refundedCents, paidAt, null);
    }

    /** 兼容旧 19 字段构造（无设备名/商户名/退款额冗余）。 */
    public AdminOrderSummaryDto(
            String orderId,
            String sessionId,
            Long userId,
            String deviceId,
            String merchantId,
            int totalAmountCents,
            int originalAmountCents,
            int couponDiscountCents,
            int memberDiscountCents,
            String status,
            String payChannel,
            int lineCount,
            String lineSummary,
            String payTradeNo,
            String paymentOperationId,
            Instant refundedAt,
            boolean inventoryDeducted,
            String refundPolicy,
            Instant createdAt
    ) {
        this(orderId, sessionId, userId, deviceId, merchantId, totalAmountCents, originalAmountCents,
                couponDiscountCents, memberDiscountCents, status, payChannel, lineCount, lineSummary,
                payTradeNo, paymentOperationId, refundedAt, inventoryDeducted, refundPolicy, createdAt,
                null, null, 0, null, null);
    }

    /** 兼容旧 15 字段构造（无商户/会员折/原价/退款策略）。 */
    public AdminOrderSummaryDto(
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
    ) {
        this(orderId, sessionId, userId, deviceId, null, totalAmountCents,
                totalAmountCents + Math.max(0, couponDiscountCents),
                couponDiscountCents, 0, status, payChannel, lineCount, lineSummary,
                payTradeNo, paymentOperationId, refundedAt, inventoryDeducted, null, createdAt,
                null, null, 0, null, null);
    }
}
