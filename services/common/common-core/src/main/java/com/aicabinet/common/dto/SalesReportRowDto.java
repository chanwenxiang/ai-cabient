package com.aicabinet.common.dto;

public record SalesReportRowDto(
        String dimKey,
        String dimLabel,
        long orderCount,
        long qty,
        long revenueCents,
        long cogsCents,
        long marginCents
) {}
