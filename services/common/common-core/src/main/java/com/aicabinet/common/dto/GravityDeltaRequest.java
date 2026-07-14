package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record GravityDeltaRequest(
        @NotBlank String sessionId,
        @NotBlank String deviceId,
        @NotEmpty List<GravityDeltaItem> deltas
) {
    public record GravityDeltaItem(
            @NotBlank String skuId,
            int delta,
            String slotId
    ) {}
}
