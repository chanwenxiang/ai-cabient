package com.aicabinet.common.dto;

public record SkuHeatDto(
        String skuId,
        String skuName,
        long qtySold,
        long revenueCents
) {}
