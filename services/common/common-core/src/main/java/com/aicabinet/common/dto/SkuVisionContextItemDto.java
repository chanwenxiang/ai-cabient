package com.aicabinet.common.dto;

import java.util.List;

public record SkuVisionContextItemDto(
        String skuId,
        String skuName,
        int priceCents,
        String yoloClassName,
        String imageUrl,
        List<String> referenceImageUrls,
        float detectionMinConfidence,
        String visionEnrollmentStatus,
        int quantityOnDevice
) {}
