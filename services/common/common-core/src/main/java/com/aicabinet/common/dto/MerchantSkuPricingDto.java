package com.aicabinet.common.dto;

import java.time.Instant;

public record MerchantSkuPricingDto(
        String deviceId,
        String deviceName,
        String skuId,
        String skuName,
        int basePriceCents,
        Integer overridePriceCents,
        int effectivePriceCents,
        Integer minPriceCents,
        Integer maxPriceCents,
        int inventoryQty,
        Instant priceUpdatedAt
) {}
