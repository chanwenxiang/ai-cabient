package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

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
        String operationScope,
        /** 件装数（CB-061）：每件的支数，用于采购按件、库存/销售按支的换算；默认 1 表示无箱规。 */
        int packQty,
        /** 商品×场景上架限额（CB-049 扩展）：admin 读路径回填；其他消费方为 null */
        List<SkuSceneLimitItem> sceneLimits
) {}
