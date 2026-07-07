package com.aicabinet.common.dto;

public record OrderLineDto(
        String skuId,
        String skuName,
        int quantity,
        int unitPriceCents,
        int lineAmountCents
) {}
