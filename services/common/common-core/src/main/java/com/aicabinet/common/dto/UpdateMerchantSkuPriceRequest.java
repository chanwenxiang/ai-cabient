package com.aicabinet.common.dto;

public record UpdateMerchantSkuPriceRequest(
        String deviceId,
        Integer priceCents,
        /** 已有覆盖价时必填；与列表 priceVersion 一致，冲突 HTTP 409。 */
        Long expectedVersion
) {
    public UpdateMerchantSkuPriceRequest(String deviceId, Integer priceCents) {
        this(deviceId, priceCents, null);
    }
}
