package com.aicabinet.common.dto;

import java.time.Instant;

public record DeviceOpsEventDto(
        Long eventId,
        String deviceId,
        String deviceName,
        String eventType,
        String severity,
        String title,
        String detail,
        Instant createdAt
) {}
