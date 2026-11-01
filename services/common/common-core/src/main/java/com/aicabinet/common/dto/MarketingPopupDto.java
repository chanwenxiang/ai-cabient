package com.aicabinet.common.dto;

/**
 * 活动弹窗（CB-082 v1）：{@code POPUP_BEFORE_OPEN} 开门前 / {@code POPUP_AFTER_CLOSE} 关门后，
 * 两位置互斥。图片直链走 /api/v2/media/ad-assets/{assetId}。
 */
public record MarketingPopupDto(
        Long activityId,
        String title,
        String description,
        String position,
        String imageUrl,
        String ctaPath,
        Integer imageWidth,
        Integer imageHeight
) {}
