package com.aicabinet.common.dto;

import java.time.Instant;

public record ReplenishmentTaskDto(
        Long taskId,
        Long routeId,
        String deviceId,
        Long assigneeUserId,
        String status,
        String notes,
        Instant completedAt,
        Instant checkInAt,
        Double checkInLat,
        Double checkInLng,
        Long requestId,
        Long outboundId,
        Instant createdAt
) {}
