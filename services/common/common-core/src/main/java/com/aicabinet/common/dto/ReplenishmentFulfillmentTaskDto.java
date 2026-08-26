package com.aicabinet.common.dto;

import java.time.Instant;

/** 履约任务列表（含路线名称，供运营台分页展示）。 */
public record ReplenishmentFulfillmentTaskDto(
        Long taskId,
        Long routeId,
        String routeName,
        String deviceId,
        Long assigneeUserId,
        String status,
        String notes,
        Instant completedAt,
        Instant checkInAt,
        Double checkInLat,
        Double checkInLng,
        Double checkInDistanceM,
        Long requestId,
        Long outboundId,
        Instant createdAt
) {}
