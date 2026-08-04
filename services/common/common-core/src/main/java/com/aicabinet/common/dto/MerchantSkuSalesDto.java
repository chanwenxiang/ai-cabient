package com.aicabinet.common.dto;

public record MerchantSkuSalesDto(
        String skuId,
        String skuName,
        long qtySold,
        long revenueCents,
        long cogsCents,
        long grossMarginCents
) {}
