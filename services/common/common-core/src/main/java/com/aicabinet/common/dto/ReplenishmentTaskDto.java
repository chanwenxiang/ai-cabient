package com.aicabinet.common.dto;

import java.time.Instant;
import java.time.LocalDate;

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
        /** Distance from check-in GPS to device coordinates, meters; null if either side missing. */
        Double checkInDistanceM,
        Long requestId,
        Long outboundId,
        Instant createdAt,
        String deviceName,
        /** 线路名称（联 replenishment_route） */
        String routeName,
        /** 线路计划日 / 业务截止日（联 replenishment_route.planned_date） */
        LocalDate plannedDate
) {
    public ReplenishmentTaskDto(
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
            Double checkInDistanceM,
            Long requestId,
            Long outboundId,
            Instant createdAt
    ) {
        this(taskId, routeId, deviceId, assigneeUserId, status, notes, completedAt, checkInAt,
                checkInLat, checkInLng, checkInDistanceM, requestId, outboundId, createdAt,
                null, null, null);
    }

    public ReplenishmentTaskDto(
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
            Double checkInDistanceM,
            Long requestId,
            Long outboundId,
            Instant createdAt,
            String deviceName
    ) {
        this(taskId, routeId, deviceId, assigneeUserId, status, notes, completedAt, checkInAt,
                checkInLat, checkInLng, checkInDistanceM, requestId, outboundId, createdAt,
                deviceName, null, null);
    }
}
