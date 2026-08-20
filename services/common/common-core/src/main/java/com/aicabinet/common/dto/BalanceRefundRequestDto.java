package com.aicabinet.common.dto;

import java.time.Instant;

public record BalanceRefundRequestDto(
        long requestId,
        String requestNo,
        Long userId,
        int amountCents,
        String status,
        String reason,
        String reviewRemark,
        Long reviewerId,
        Instant reviewedAt,
        String failReason,
        Instant createdAt,
        Instant updatedAt,
        Instant refundedAt
) {}
