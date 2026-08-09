package com.aicabinet.common.dto;

import java.time.Instant;

public record NotificationDto(
        Long id,
        String title,
        String body,
        String templateCode,
        String channel,
        String audience,
        String bizType,
        String bizId,
        boolean read,
        Instant readAt,
        Instant createdAt
) {}
