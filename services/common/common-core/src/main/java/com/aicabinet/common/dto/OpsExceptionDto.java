package com.aicabinet.common.dto;

import java.time.Instant;

public record OpsExceptionDto(
        String exceptionId, String exceptionType, String severity, String status,
        String deviceId, String sessionId, String orderId, Long userId,
        String title, String detail, Long assigneeUserId, String resolution,
        Instant createdAt, Instant updatedAt, Instant resolvedAt,
        Instant slaDueAt, boolean slaOverdue
) {
    public OpsExceptionDto(
            String exceptionId, String exceptionType, String severity, String status,
            String deviceId, String sessionId, String orderId, Long userId,
            String title, String detail, Long assigneeUserId, String resolution,
            Instant createdAt, Instant updatedAt, Instant resolvedAt
    ) {
        this(exceptionId, exceptionType, severity, status, deviceId, sessionId, orderId, userId,
                title, detail, assigneeUserId, resolution, createdAt, updatedAt, resolvedAt, null, false);
    }
}
