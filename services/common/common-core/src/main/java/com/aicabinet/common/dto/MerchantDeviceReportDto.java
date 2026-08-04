package com.aicabinet.common.dto;

public record MerchantDeviceReportDto(
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
