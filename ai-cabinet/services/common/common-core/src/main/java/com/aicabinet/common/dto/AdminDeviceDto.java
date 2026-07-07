package com.aicabinet.common.dto;

import java.time.Instant;

public record AdminDeviceDto(
        String deviceId,
        String deviceName,
        String deviceType,
        String onlineStatus,
        String activeSessionId,
        String activeSessionState,
        Instant updatedAt
) {}
