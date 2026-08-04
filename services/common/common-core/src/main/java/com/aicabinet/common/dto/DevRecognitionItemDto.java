package com.aicabinet.common.dto;

public record DevRecognitionItemDto(
        String skuId,
        String skuName,
        int quantity,
        float confidence,
        Integer unitPriceCents,
        Integer lineAmountCents
) {
    public DevRecognitionItemDto(String skuId, String skuName, int quantity, float confidence) {
        this(skuId, skuName, quantity, confidence, null, null);
    }
}
