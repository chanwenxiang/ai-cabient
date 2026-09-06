package com.aicabinet.common.dto;

public record ScreenContentItemDto(
        Long assetId,
        String title,
        String assetType,
        String storageUri,
        int durationSeconds,
        /** 浏览器/小程序可直接播放的同源代理路径（如 /api/v2/media/ad-assets/{id}）。 */
        String playUrl
) {}
