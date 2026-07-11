package com.aicabinet.common.dto;

import java.time.Instant;

public record OpsExceptionDto(
        String exceptionId, String exceptionType, String severity, String status,
        String deviceId, String sessionId, String orderId, Long userId,
        String title, String detail, Long assigneeUserId, String resolution,
        Instant createdAt, Instant updatedAt, Instant resolvedAt
) {}
