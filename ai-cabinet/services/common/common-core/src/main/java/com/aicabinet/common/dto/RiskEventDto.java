package com.aicabinet.common.dto;

import java.time.Instant;

public record RiskEventDto(
        Long eventId,
        Long userId,
        String deviceId,
        String eventType,
        String severity,
        String detail,
        Instant createdAt
) {}
