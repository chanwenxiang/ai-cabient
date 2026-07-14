package com.aicabinet.common.dto;

import java.time.Instant;

public record RevenueSplitDto(
        String splitId,
        String orderId,
        String merchantId,
        String merchantName,
        String deviceId,
        long grossCents,
        long platformCents,
        long merchantCents,
        String status,
        String wechatOutOrderNo,
        String wechatTransactionId,
        String failureReason,
        Instant createdAt,
        String settlementBatchNo,
        java.time.LocalDate settleAfter,
        Instant settledAt
) {}
