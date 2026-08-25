package com.aicabinet.common.dto;

public record HourlyHeatDto(
        int hour,
        long orders,
        long revenueCents
) {}
