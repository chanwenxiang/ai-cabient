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
        String photoEvidenceUrl,
        /** 客户端看到的库存版本；已有库存行时必填，冲突返回 409。 */
        Long expectedVersion
) {
    public StocktakeAdjustRequest(String deviceId, String skuId, Integer countedQuantity, String note) {
        this(deviceId, skuId, countedQuantity, note, null, null);
    }

    public StocktakeAdjustRequest(
            String deviceId, String skuId, Integer countedQuantity, String note, String photoEvidenceUrl) {
        this(deviceId, skuId, countedQuantity, note, photoEvidenceUrl, null);
    }
}
