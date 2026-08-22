package com.aicabinet.common.dto;

import java.time.Instant;

public record RiskEventDto(
        Long eventId,
        Long userId,
        String deviceId,
        String eventType,
        String severity,
        String detail,
        Instant createdAt,
        String dispositionStatus,
        Instant dispositionAt,
        String dispositionNote
) {
    public RiskEventDto(Long eventId, Long userId, String deviceId, String eventType,
                        String severity, String detail, Instant createdAt) {
        this(eventId, userId, deviceId, eventType, severity, detail, createdAt, "OPEN", null, null);
    }
}
