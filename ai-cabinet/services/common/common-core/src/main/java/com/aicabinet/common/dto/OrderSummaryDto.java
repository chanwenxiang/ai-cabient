package com.aicabinet.common.dto;

import java.time.Instant;

/** 用户端订单列表摘要 */
public record OrderSummaryDto(
        String orderId,
        String sessionId,
        String deviceId,
        int totalAmountCents,
        String status,
        int lineCount,
        Instant createdAt
) {}
