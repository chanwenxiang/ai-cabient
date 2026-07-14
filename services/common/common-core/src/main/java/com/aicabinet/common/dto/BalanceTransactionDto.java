package com.aicabinet.common.dto;

import java.time.Instant;

public record BalanceTransactionDto(
        String transactionId,
        Long userId,
        String businessType,
        String businessId,
        int amountCents,
        int balanceBeforeCents,
        int balanceAfterCents,
        String reason,
        Instant createdAt
) {}
