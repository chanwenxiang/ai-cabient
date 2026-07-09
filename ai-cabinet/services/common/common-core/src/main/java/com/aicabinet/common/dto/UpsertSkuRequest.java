package com.aicabinet.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpsertSkuRequest(
        @NotBlank String skuId,
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
        Integer nearExpiryPriceCents
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
    }
}
