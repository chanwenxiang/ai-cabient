package com.aicabinet.common.dto;

public record MerchantDailySettlementDto(
        String date,
        long orderCount,
        long grossCents,
        long platformCents,
        long merchantCents,
        long settledCents,
        long pendingCents,
        long failedCount
) {}
