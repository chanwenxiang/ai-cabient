package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record UpsertWarehouseBinRequest(
        @NotBlank String warehouseId,
        @NotBlank String binCode,
        String binName,
        String status
) {}
