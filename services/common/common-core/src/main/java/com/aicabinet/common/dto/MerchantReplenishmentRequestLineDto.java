package com.aicabinet.common.dto;

public record MerchantReplenishmentRequestLineDto(
        Long lineId,
        String skuId,
        String skuName,
        int suggestedQty,
        int requestedQty
) {}
