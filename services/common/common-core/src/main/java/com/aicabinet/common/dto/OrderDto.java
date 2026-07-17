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
        Integer pointsEarned
) {
    /** 兼容旧调用（无优惠券/积分字段）。 */
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
                paymentOperationId, balanceBeforeCents, balanceAfterCents, createdAt, null, null, null);
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
                couponDiscountCents, originalAmountCents, null);
    }
}
