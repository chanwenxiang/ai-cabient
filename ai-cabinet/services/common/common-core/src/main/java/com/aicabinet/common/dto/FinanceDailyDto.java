package com.aicabinet.common.dto;

import java.util.List;

public record FinanceDailyDto(
        String date,
        long revenueCents,
        long cogsCents,
        long grossMarginCents,
        long writeOffCents
) {}
