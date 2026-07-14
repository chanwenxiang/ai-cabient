package com.aicabinet.common.dto;

public record UpdateMerchantSkuPriceRequest(
        String deviceId,
        Integer priceCents
) {}
