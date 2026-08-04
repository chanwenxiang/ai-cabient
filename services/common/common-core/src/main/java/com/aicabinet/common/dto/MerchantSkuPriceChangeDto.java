package com.aicabinet.common.dto;

import java.time.Instant;

public record MerchantSkuPriceChangeDto(
        String deviceId,
        String skuId,
        String detail,
        Instant changedAt
) {}
