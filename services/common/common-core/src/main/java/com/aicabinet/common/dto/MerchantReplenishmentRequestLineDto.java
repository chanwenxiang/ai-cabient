package com.aicabinet.common.dto;

public record MerchantReplenishmentRequestLineDto(
        Long lineId,
        String skuId,
        String skuName,
        String spec,
        int suggestedQty,
        int requestedQty
) {}
