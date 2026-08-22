package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateSessionRequest(
        @NotBlank String deviceId,
        @NotBlank String idempotencyKey,
        String entryChannel,
        Long preferredCouponId
) {
    public CreateSessionRequest(String deviceId, String idempotencyKey) {
        this(deviceId, idempotencyKey, null, null);
    }

    public CreateSessionRequest(String deviceId, String idempotencyKey, String entryChannel) {
        this(deviceId, idempotencyKey, entryChannel, null);
    }
}
