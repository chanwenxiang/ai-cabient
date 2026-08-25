package com.aicabinet.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StocktakeAdjustRequest(
        @NotBlank String deviceId,
        @NotBlank String skuId,
        @NotNull @Min(0) Integer countedQuantity,
        String note,
        /** 盘点照片凭证；商户开启 photoStocktake 时必填 */
        String photoEvidenceUrl
) {
    public StocktakeAdjustRequest(String deviceId, String skuId, Integer countedQuantity, String note) {
        this(deviceId, skuId, countedQuantity, note, null);
    }
}
