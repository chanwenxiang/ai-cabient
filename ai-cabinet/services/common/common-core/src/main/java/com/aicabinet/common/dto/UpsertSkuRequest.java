package com.aicabinet.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record UpsertSkuRequest(
        @NotBlank String skuId,
        @NotBlank String skuName,
        @Min(1) int priceCents
) {}
