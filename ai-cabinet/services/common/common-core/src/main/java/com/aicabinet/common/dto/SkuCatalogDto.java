package com.aicabinet.common.dto;

import java.time.Instant;

public record SkuCatalogDto(
        String skuId,
        String skuName,
        int priceCents,
        Integer weightGrams,
        boolean visionEnabled,
        String imageUrl,
        String description,
        String category,
        String barcode,
        String status,
        Integer shelfLifeDays,
        int nearExpiryDays,
        int blockSaleDaysBeforeExpiry,
        String storageType,
        Integer purchaseCostCents,
        Integer nearExpiryPriceCents,
        Instant createdAt
) {}
