package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OpsOpenDoorRequest(
        @NotBlank String deviceId,
        @NotNull Long taskId
) {}
