package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FileDisputeRequest(
        @NotBlank String sessionId,
        @NotBlank @Size(max = 256) String reason,
        String category,
        String priority
) {}
