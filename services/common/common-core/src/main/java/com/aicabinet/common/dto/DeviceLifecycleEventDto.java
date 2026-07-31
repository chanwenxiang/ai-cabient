package com.aicabinet.common.dto;

import java.time.Instant;

public record DeviceLifecycleEventDto(
        Long eventId,
        String deviceId,
        String fromStatus,
        String toStatus,
        String action,
        Long operatorId,
        String remark,
        Instant createdAt
) {}
