package com.aicabinet.common.dto;

import com.aicabinet.common.enums.SessionState;

import java.time.Instant;

public record AdminSessionDto(
        String sessionId,
        Long userId,
        String deviceId,
        SessionState state,
        Instant openTime,
        Instant closeTime,
        String orderId,
        String videoUri,
        String uploadStatus,
        String cameraFusionMode,
        String videoPreviewUrl,
        String failReason,
        Instant createdAt,
        Instant updatedAt,
        /** CONSUMER / RESTOCK / OPS */
        String sessionKind,
        Long replenishmentTaskId,
        /** WECHAT / ALIPAY / … 开门入口渠道 */
        String entryChannel,
        String payChannel,
        /** 开门预授权金额（分） */
        Integer preauthCents,
        /** NONE / FROZEN / CAPTURED / RELEASED */
        String preauthStatus,
        /** 开门→关门时长（毫秒），无关门则为 null */
        Long shoppingDurationMs,
        /** 关门→会话更新（识别/结算）时长（毫秒） */
        Long recognitionDurationMs
) {
    public AdminSessionDto(
            String sessionId,
            Long userId,
            String deviceId,
            SessionState state,
            Instant openTime,
            Instant closeTime,
            String orderId,
            String videoUri,
            String uploadStatus,
            String cameraFusionMode,
            String videoPreviewUrl,
            String failReason,
            Instant createdAt,
            Instant updatedAt,
            String sessionKind,
            Long replenishmentTaskId
    ) {
        this(sessionId, userId, deviceId, state, openTime, closeTime, orderId, videoUri,
                uploadStatus, cameraFusionMode, videoPreviewUrl, failReason, createdAt, updatedAt,
                sessionKind, replenishmentTaskId, null, null, null, null, null, null);
    }

    public AdminSessionDto(
            String sessionId,
            Long userId,
            String deviceId,
            SessionState state,
            Instant openTime,
            Instant closeTime,
            String orderId,
            String videoUri,
            String uploadStatus,
            String cameraFusionMode,
            String videoPreviewUrl,
            String failReason,
            Instant createdAt,
            Instant updatedAt,
            String sessionKind,
            Long replenishmentTaskId,
            String entryChannel,
            String payChannel
    ) {
        this(sessionId, userId, deviceId, state, openTime, closeTime, orderId, videoUri,
                uploadStatus, cameraFusionMode, videoPreviewUrl, failReason, createdAt, updatedAt,
                sessionKind, replenishmentTaskId, entryChannel, payChannel, null, null, null, null);
    }
}
