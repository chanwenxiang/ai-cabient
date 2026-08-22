package com.aicabinet.common.dto;

import java.time.Instant;
import java.time.LocalDate;

public record LinePromoTaskDto(
        Long taskId,
        Long managerId,
        String title,
        String routeCode,
        int targetQty,
        int doneQty,
        int bountyCents,
        String status,
        LocalDate dueDate,
        Instant updatedAt
) {}
