package com.aicabinet.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record WriteOffRequest(
        @NotBlank String deviceId,
        @NotBlank String skuId,
        String batchNo,
        @NotNull @Min(1) Integer quantity,
        @NotBlank String reason
) {}
