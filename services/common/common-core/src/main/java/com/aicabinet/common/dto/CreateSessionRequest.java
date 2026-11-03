package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSessionRequest(
        @NotBlank String deviceId,
        @NotBlank @Size(max = 128) String idempotencyKey,
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
