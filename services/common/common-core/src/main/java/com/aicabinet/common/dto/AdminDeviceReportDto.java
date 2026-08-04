package com.aicabinet.common.dto;

public record AdminDeviceReportDto(
        String deviceId,
        String deviceName,
        String onlineStatus,
        long orderTotal,
        long revenueTotalCents,
        long orderToday,
        long revenueTodayCents,
        long sessionTotal,
        long sessionActive
) {}
