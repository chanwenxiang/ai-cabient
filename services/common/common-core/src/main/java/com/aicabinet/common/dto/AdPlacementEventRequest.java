package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 展示位打点（批次 S）：首页 banner / 开门前弹窗 / 关门后弹窗。
 *
 * <p>{@code activityId}（PromotionActivity）与 {@code campaignId}（AdCampaign）**分列**上报：
 * 弹窗位只有活动、banner 两者都可能带，把两种 id 塞进同一列就是此前
 * {@code MP-U{userId}} 塞 device_id 的同类错误。主体不在此 DTO 里——一律取登录态。
 */
public record AdPlacementEventRequest(
        @NotBlank String position,
        Long activityId,
        Long campaignId,
        Long assetId,
        @NotBlank String eventType
) {}
