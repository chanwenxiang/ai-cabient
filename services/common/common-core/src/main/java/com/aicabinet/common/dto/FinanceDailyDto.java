package com.aicabinet.common.dto;

public record FinanceDailyDto(
        String date,
        long revenueCents,
        long cogsCents,
        long grossMarginCents,
        long writeOffCents
) {}
