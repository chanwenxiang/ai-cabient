package com.aicabinet.common.dto;

import java.time.Instant;

public record FinanceMarginLockDto(
        String bizDate,
        long revenueCents,
        long cogsCents,
        long marginCents,
        long writeOffCents,
        long orderCount,
        Instant lockedAt,
        Long lockedBy,
        boolean locked
) {}
