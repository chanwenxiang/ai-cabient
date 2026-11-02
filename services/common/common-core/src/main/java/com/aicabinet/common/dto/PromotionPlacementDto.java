package com.aicabinet.common.dto;

/**
 * 活动展示位（CB-082 v1）：轮播横幅 + 开门前/关门后弹窗（两个弹窗位互斥，只能二选一）。
 */
public record PromotionPlacementDto(
        Long placementId,
        Long activityId,
        /** BANNER / POPUP_BEFORE_OPEN / POPUP_AFTER_CLOSE（字典 promotion_placement_position） */
        String position,
        Long assetId,
        Integer imageWidth,
        Integer imageHeight,
        /** 轮播顺序：数值小在前（V434） */
        Integer sortOrder,
        /** 同源素材预览地址 /api/v2/media/ad-assets/{assetId} */
        String previewUrl
) {}
