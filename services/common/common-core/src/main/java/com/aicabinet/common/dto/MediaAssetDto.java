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
        Instant createdAt,
        /** 图片像素尺寸；视频或无法解析时为 null（CB-082 展示位校验用）。 */
        Integer imageWidth,
        Integer imageHeight
) {}
