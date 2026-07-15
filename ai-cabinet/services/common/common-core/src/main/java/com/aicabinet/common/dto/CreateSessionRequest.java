package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateSessionRequest(
        @NotBlank String deviceId,
        String idempotencyKey
) {}
