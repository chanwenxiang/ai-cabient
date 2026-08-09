package com.aicabinet.common.dto;

public record ReplenishmentStaffRowDto(
        Long userId,
        String name,
        String phone,
        long totalTasks,
        long completedTasks,
        double completionRate,
        Double avgDurationMinutes,
        long openTasks,
        double avgDailyTasks
) {}
