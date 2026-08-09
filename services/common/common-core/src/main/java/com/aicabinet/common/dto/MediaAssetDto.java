package com.aicabinet.common.dto;

import java.time.Instant;

public record MediaAssetDto(
        Long assetId,
        String title,
        String assetType,
        String storageUri,
        String previewUrl,
        int durationSeconds,
        String status,
        Instant createdAt
) {}
