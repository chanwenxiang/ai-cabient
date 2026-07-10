package com.aicabinet.common.dto;

import java.time.Instant;

public record DeviceTemperatureReadingDto(
        String deviceId,
        int tempC,
        Instant reportedAt
) {}
