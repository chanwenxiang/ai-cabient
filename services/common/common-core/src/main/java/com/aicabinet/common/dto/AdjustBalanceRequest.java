package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdjustBalanceRequest(
        int deltaCents,
        @NotBlank String reason,
        @NotBlank @Size(max = 128) String idempotencyKey
) {}
