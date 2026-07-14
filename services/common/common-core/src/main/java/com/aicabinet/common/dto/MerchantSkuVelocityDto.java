package com.aicabinet.common.dto;

public record MerchantSkuVelocityDto(
        String skuId,
        String skuName,
        int soldQty7d,
        int soldQty14d,
        double avgDailySales,
        int ropPoint
) {}
