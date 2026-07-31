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
        Instant updatedAt
) {}
