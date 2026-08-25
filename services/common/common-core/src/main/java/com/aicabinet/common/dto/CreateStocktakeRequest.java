package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateStocktakeRequest(
        @NotBlank String warehouseId,
        String mode,
        String notes
) {}
