package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record UpsertDeviceRequest(
        @NotBlank String deviceId,
        String deviceName,
        String deviceType
) {}
