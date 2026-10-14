package com.aicabinet.common.dto;

import java.time.Instant;

public record SkuCatalogDto(
        String skuId,
        Long skuCode,
        String skuName,
        int priceCents,
        Integer weightGrams,
        boolean visionEnabled,
        String imageUrl,
        String description,
        String category,
        String barcode,
        String brand,
        String spec,
        String unit,
        String status,
        Integer shelfLifeDays,
        int nearExpiryDays,
        int blockSaleDaysBeforeExpiry,
        String storageType,
        Integer purchaseCostCents,
        Integer nearExpiryPriceCents,
        Integer maxPriceCents,
        /** 显式最低限价（V350，CB-037）：null=不限，仅要求 >0；支持打折/清仓，低于成本前端提醒 */
        Integer minPriceCents,
        float minChargeConfidence,
        Float detectionMinConfidence,
        Instant createdAt,
        Long updatedByUserId,
        String updatedByName,
        /** 经营归属（V349 字典 sku_operation_scope：DIRECT/NON_DIRECT；null=未分类） */
        String operationScope
) {}
