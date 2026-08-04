package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateSessionRequest(
        @NotBlank String deviceId,
        @NotBlank String idempotencyKey,
        String entryChannel
) {
    public CreateSessionRequest(String deviceId, String idempotencyKey) {
        this(deviceId, idempotencyKey, null);
    }
}
