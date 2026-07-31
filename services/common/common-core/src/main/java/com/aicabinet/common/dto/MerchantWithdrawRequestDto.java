package com.aicabinet.common.dto;

import java.time.Instant;

public record MerchantWithdrawRequestDto(
        Long requestId,
        String requestNo,
        String merchantId,
        String merchantName,
        Long amountCents,
        String status,
        String payChannel,
        Long reviewerId,
        String reviewRemark,
        Instant reviewedAt,
        String payoutRef,
        String payoutMessage,
        Instant paidAt,
        Instant createdAt,
        Instant updatedAt
) {}
