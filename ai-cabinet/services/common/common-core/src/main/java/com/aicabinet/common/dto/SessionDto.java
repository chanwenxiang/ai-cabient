package com.aicabinet.common.dto;

import com.aicabinet.common.enums.SessionState;

import java.time.Instant;

public record SessionDto(
        String sessionId,
        Long userId,
        String deviceId,
        SessionState state,
        Instant openTime,
        Instant closeTime,
        String orderId,
        Instant createdAt
) {}
