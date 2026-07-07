package com.aicabinet.common.dto;

public record SkuCatalogDto(
        String skuId,
        String skuName,
        int priceCents
) {}
