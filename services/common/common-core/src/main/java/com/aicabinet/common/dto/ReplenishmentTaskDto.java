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
        LocalDate plannedDate,
        /** 现场凭证张数（列表聚合，避免 N+1） */
        Integer evidenceCount,
        /** 明细行摘要（列表聚合） */
        String lineSummary,
        /**
         * 柜机是否已录入点位坐标。null=未知（未联查）。
         * false 时签到**必被拒**（{@code REPLENISHMENT_CHECK_IN_DEVICE_LOCATION_MISSING}，400），
         * 客户端据此**前置禁用签到**并提示「联系运营补录坐标」，而不是等到柜前吃 400。
         */
        Boolean deviceHasCoords
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
                null, null, null, null, null, null);
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
                deviceName, null, null, null, null, null);
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
            String deviceName,
            String routeName,
            LocalDate plannedDate
    ) {
        this(taskId, routeId, deviceId, assigneeUserId, status, notes, completedAt, checkInAt,
                checkInLat, checkInLng, checkInDistanceM, requestId, outboundId, createdAt,
                deviceName, routeName, plannedDate, null, null, null);
    }
}
