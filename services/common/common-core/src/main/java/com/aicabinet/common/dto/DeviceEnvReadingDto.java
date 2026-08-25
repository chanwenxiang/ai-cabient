package com.aicabinet.common.dto;

import java.time.Instant;

public record DeviceEnvReadingDto(
        String deviceId,
        String metricType,
        double value,
        Instant reportedAt
) {}
