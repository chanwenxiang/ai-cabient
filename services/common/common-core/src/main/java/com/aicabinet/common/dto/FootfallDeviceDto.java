package com.aicabinet.common.dto;

public record FootfallDeviceDto(
        String deviceId,
        String deviceName,
        long opens,
        long orders,
        long revenueCents,
        double conversionRate,
        long revenuePerDeviceCents
) {}
