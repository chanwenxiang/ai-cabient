package com.aicabinet.common.dto;

import java.time.Instant;

public record OpsActionItemDto(
        String type,
        String severity,
        String title,
        String detail,
        String deviceId,
        String sessionId,
        String ticketId,
        String skuId,
        Long taskId,
        Instant createdAt,
        Instant dueAt
) {}
