package com.aicabinet.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SlotStocktakeRequest(
        @NotBlank String slotCode,
        @Min(0) int physicalQty
) {}
