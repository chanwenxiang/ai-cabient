package com.aicabinet.common.dto;

public record UpdateMerchantSkuPriceRequest(
        String deviceId,
        Integer priceCents,
        /** 客户端看到的改价版本；空则仅依赖服务端行锁/当前行版本。 */
        Long expectedVersion
) {
    public UpdateMerchantSkuPriceRequest(String deviceId, Integer priceCents) {
        this(deviceId, priceCents, null);
    }
}
