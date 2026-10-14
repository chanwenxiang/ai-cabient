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
        Long outboundId,
        Instant createdAt,
        String deviceName,
        /** 未分配货道的 RESTOCK 行数（applied=false 且 slot_id 空；0=全部就绪，CB-038 2026-10-14 重建）。 */
        int unassignedRestockLines
) {
    public ReplenishmentFulfillmentTaskDto(
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
            Long outboundId,
            Instant createdAt
    ) {
        this(taskId, routeId, routeName, deviceId, assigneeUserId, status, notes, completedAt,
                checkInAt, checkInLat, checkInLng, checkInDistanceM, outboundId, createdAt, null, 0);
    }
}
