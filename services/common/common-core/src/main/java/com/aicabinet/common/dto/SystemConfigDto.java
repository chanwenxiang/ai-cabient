package com.aicabinet.common.dto;

import java.time.Instant;

public record SystemConfigDto(
        String configKey,
        String configValue,
        String description,
        Instant updatedAt
) {}
