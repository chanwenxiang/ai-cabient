package com.aicabinet.common.dto;

public record DevRecognitionItemDto(
        String skuId,
        String skuName,
        int quantity,
        float confidence
) {}
