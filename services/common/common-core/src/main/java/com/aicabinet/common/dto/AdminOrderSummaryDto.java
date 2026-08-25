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
        Instant createdAt
) {
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
                payTradeNo, paymentOperationId, refundedAt, inventoryDeducted, null, createdAt);
    }
}
