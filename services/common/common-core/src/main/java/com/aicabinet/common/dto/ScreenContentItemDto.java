package com.aicabinet.common.dto;

public record ScreenContentItemDto(
        Long assetId,
        String title,
        String assetType,
        String storageUri,
        int durationSeconds
) {}
