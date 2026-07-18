package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

public record OrderDto(
        String orderId,
        String sessionId,
        Long userId,
        String deviceId,
        int totalAmountCents,
        List<OrderLineDto> lines,
        String status,
        String payChannel,
        String paymentOperationId,
        Integer balanceBeforeCents,
        Integer balanceAfterCents,
        Instant createdAt,
        Integer couponDiscountCents,
        Integer originalAmountCents,
        Integer pointsEarned,
        /** 该订单所属柜机生效退款策略：AUTO_REFUND | DISPUTE_ONLY */
        String refundPolicy
) {
    /** 兼容旧调用（无优惠券/积分/策略字段）。 */
    public OrderDto(
            String orderId,
            String sessionId,
            Long userId,
            String deviceId,
            int totalAmountCents,
            List<OrderLineDto> lines,
            String status,
            String payChannel,
            String paymentOperationId,
            Integer balanceBeforeCents,
            Integer balanceAfterCents,
            Instant createdAt
    ) {
        this(orderId, sessionId, userId, deviceId, totalAmountCents, lines, status, payChannel,
                paymentOperationId, balanceBeforeCents, balanceAfterCents, createdAt, null, null, null, null);
    }

    /** 兼容仅含优惠券字段的调用。 */
    public OrderDto(
            String orderId,
            String sessionId,
            Long userId,
            String deviceId,
            int totalAmountCents,
            List<OrderLineDto> lines,
            String status,
            String payChannel,
            String paymentOperationId,
            Integer balanceBeforeCents,
            Integer balanceAfterCents,
            Instant createdAt,
            Integer couponDiscountCents,
            Integer originalAmountCents
    ) {
        this(orderId, sessionId, userId, deviceId, totalAmountCents, lines, status, payChannel,
                paymentOperationId, balanceBeforeCents, balanceAfterCents, createdAt,
                couponDiscountCents, originalAmountCents, null, null);
    }

    /** 兼容含积分、无策略字段的调用。 */
    public OrderDto(
            String orderId,
            String sessionId,
            Long userId,
            String deviceId,
            int totalAmountCents,
            List<OrderLineDto> lines,
            String status,
            String payChannel,
            String paymentOperationId,
            Integer balanceBeforeCents,
            Integer balanceAfterCents,
            Instant createdAt,
            Integer couponDiscountCents,
            Integer originalAmountCents,
            Integer pointsEarned
    ) {
        this(orderId, sessionId, userId, deviceId, totalAmountCents, lines, status, payChannel,
                paymentOperationId, balanceBeforeCents, balanceAfterCents, createdAt,
                couponDiscountCents, originalAmountCents, pointsEarned, null);
    }
}
