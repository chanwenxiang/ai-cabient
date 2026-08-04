package com.aicabinet.common.dto;

import java.time.Instant;

public record LineWithdrawRequestDto(
        Long requestId,
        String requestNo,
        Long managerId,
        String managerName,
        String phone,
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
