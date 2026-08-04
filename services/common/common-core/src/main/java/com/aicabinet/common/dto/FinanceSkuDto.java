package com.aicabinet.common.dto;

public record FinanceSkuDto(
        String skuId,
        String skuName,
        long qtySold,
        long revenueCents,
        long cogsCents,
        long grossMarginCents
) {}
