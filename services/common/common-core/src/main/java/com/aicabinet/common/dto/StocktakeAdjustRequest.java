package com.aicabinet.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StocktakeAdjustRequest(
        @NotBlank String deviceId,
        @NotBlank String skuId,
        @NotNull @Min(0) Integer countedQuantity,
        String note
) {}
