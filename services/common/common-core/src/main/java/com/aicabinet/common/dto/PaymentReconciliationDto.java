package com.aicabinet.common.dto;

import java.time.Instant;
import java.time.LocalDate;

public record PaymentReconciliationDto(
        Long reconId,
        LocalDate reconDate,
        String channel,
        long platformTotal,
        long ledgerTotal,
        long diffCents,
        int matchedCount,
        int unmatchedCount,
        String status,
        Instant createdAt,
        Instant completedAt
) {}
