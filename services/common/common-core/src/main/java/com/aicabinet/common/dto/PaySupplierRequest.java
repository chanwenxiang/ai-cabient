package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PaySupplierRequest(
        @NotNull Long amountCents,
        String notes,
        @NotBlank @Size(max = 64) String idempotencyKey
) {}
