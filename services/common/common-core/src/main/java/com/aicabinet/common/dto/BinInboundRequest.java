package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record BinInboundRequest(
        @NotBlank String warehouseId,
        @NotBlank String binCode,
        @NotBlank String skuId,
        @NotBlank String batchNo,
        LocalDate productionDate,
        LocalDate expiryDate,
        @NotNull Integer quantity
) {}
