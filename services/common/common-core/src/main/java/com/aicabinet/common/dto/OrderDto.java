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

        /** 该订单所属柜机生效退款策略：AUTO_REFUND | DISPUTE_ONLY */

        String refundPolicy,

        Integer memberDiscountCents,

        String payTradeNo,

        Instant refundedAt,

        Boolean inventoryDeducted,

        String merchantId,

        /** 累计已退款金额（分） */

        Integer refundedCents

) {

    /** 兼容旧调用（无优惠券/策略字段）。 */

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

                paymentOperationId, balanceBeforeCents, balanceAfterCents, createdAt,

                null, null, null, null, null, null, null, null, null);

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

                couponDiscountCents, originalAmountCents, null, null, null, null, null, null, null);

    }



    /** 含退款策略的调用。 */

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

            String refundPolicy

    ) {

        this(orderId, sessionId, userId, deviceId, totalAmountCents, lines, status, payChannel,

                paymentOperationId, balanceBeforeCents, balanceAfterCents, createdAt,

                couponDiscountCents, originalAmountCents, refundPolicy, null, null, null, null, null, null);

    }



    /** 含商户号、无累计退款额。 */

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

            String refundPolicy,

            Integer memberDiscountCents,

            String payTradeNo,

            Instant refundedAt,

            Boolean inventoryDeducted,

            String merchantId

    ) {

        this(orderId, sessionId, userId, deviceId, totalAmountCents, lines, status, payChannel,

                paymentOperationId, balanceBeforeCents, balanceAfterCents, createdAt,

                couponDiscountCents, originalAmountCents, refundPolicy, memberDiscountCents,

                payTradeNo, refundedAt, inventoryDeducted, merchantId, null);

    }

}


