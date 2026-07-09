package com.aicabinet.common.dto;

public record DeviceProductDto(
        String skuId,
        String skuName,
        int priceCents,
        int quantity,
        String imageUrl,
        String category,
        String description
) {}
