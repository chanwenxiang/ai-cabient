package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateStocktakeLineRequest(
        @NotNull Integer countedQty,
        String notes
) {}
