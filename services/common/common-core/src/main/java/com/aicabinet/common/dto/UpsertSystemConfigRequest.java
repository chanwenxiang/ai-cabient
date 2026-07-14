package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertSystemConfigRequest(
        @NotBlank @Size(max = 64) String configKey,
        @NotBlank @Size(max = 2048) String configValue,
        @Size(max = 256) String description
) {}
