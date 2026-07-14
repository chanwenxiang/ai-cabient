package com.aicabinet.common.dto;

public record MerchantExpirySummaryDto(
        long openPullOffTasks,
        long writeOffQty30d,
        long writeOffCostCents30d
) {}
