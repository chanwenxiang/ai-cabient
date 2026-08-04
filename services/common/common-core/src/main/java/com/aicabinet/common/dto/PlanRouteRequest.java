package com.aicabinet.common.dto;

import java.time.LocalDate;
import java.util.List;

public record PlanRouteRequest(
        String routeName,
        Long assigneeUserId,
        LocalDate plannedDate,
        List<String> deviceIds,
        Double startLatitude,
        Double startLongitude
) {}
