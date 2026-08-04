package com.aicabinet.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record RechargeRequest(
        @NotBlank String channel,
        @Min(1) int amountCents
) {}
