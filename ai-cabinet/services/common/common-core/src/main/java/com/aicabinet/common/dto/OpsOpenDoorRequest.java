package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record OpsOpenDoorRequest(
        @NotBlank String deviceId
) {}
