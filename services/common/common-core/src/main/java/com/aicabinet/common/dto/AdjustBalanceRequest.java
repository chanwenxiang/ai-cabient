package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record AdjustBalanceRequest(
        int deltaCents,
        @NotBlank String reason,
        @NotBlank String idempotencyKey
) {}
