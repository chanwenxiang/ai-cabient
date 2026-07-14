package com.aicabinet.common.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ReplenishmentRouteDto(
        Long routeId,
        String routeName,
        Long assigneeUserId,
        LocalDate plannedDate,
        String status,
        List<ReplenishmentTaskDto> tasks,
        Instant createdAt,
        Integer totalDistanceM,
        List<RouteWaypointDto> waypoints
) {
    public ReplenishmentRouteDto(Long routeId, String routeName, Long assigneeUserId,
                                 LocalDate plannedDate, String status,
                                 List<ReplenishmentTaskDto> tasks, Instant createdAt) {
        this(routeId, routeName, assigneeUserId, plannedDate, status, tasks, createdAt, null, List.of());
    }
}
