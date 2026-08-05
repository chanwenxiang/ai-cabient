package com.aicabinet.common.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpsertSkuRequest(
        /** 内部主键；新建时可空，服务端生成 SKU-{skuCode} */
        String skuId,
        @NotBlank String skuName,
        @Min(1) int priceCents,
        Integer weightGrams,
        Boolean visionEnabled,
        String imageUrl,
        String description,
        String category,
        String barcode,
        @Pattern(regexp = "ACTIVE|INACTIVE", message = "status must be ACTIVE or INACTIVE")
        String status,
        Integer shelfLifeDays,
        Integer nearExpiryDays,
        Integer blockSaleDaysBeforeExpiry,
        @Pattern(regexp = "AMBIENT|CHILLED|FROZEN", message = "storageType must be AMBIENT, CHILLED or FROZEN")
        String storageType,
        Integer purchaseCostCents,
        Integer nearExpiryPriceCents,
        @DecimalMin(value = "0.5", message = "minChargeConfidence must be >= 0.5")
        @DecimalMax(value = "1.0", message = "minChargeConfidence must be <= 1.0")
        Float minChargeConfidence,
        String yoloClassName,
        @Pattern(regexp = "DRAFT|MAPPING|TESTED|PRODUCTION", message = "invalid visionEnrollmentStatus")
        String visionEnrollmentStatus,
        @DecimalMin(value = "0.1", message = "detectionMinConfidence must be >= 0.1")
        @DecimalMax(value = "1.0", message = "detectionMinConfidence must be <= 1.0")
        Float detectionMinConfidence,
        String referenceImageUrlsJson,
        /** 客户端传入会被忽略；仅兼容字段 */
        Long skuCode,
        String brand,
        String spec,
        String unit
) {
    public UpsertSkuRequest {
        if (visionEnabled == null) {
            visionEnabled = true;
        }
        if (status == null || status.isBlank()) {
            status = "ACTIVE";
        }
        if (nearExpiryDays == null) {
            nearExpiryDays = 7;
        }
        if (blockSaleDaysBeforeExpiry == null) {
            blockSaleDaysBeforeExpiry = 0;
        }
        if (storageType == null || storageType.isBlank()) {
            storageType = "AMBIENT";
        }
        if (unit == null || unit.isBlank()) {
            unit = "件";
        }
    }
}
