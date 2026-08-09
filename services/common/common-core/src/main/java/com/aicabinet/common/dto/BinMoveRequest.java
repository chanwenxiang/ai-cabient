package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record BinMoveRequest(
        @NotNull Long fromBinId,
        @NotNull Long toBinId,
        @NotBlank String skuId,
        @NotBlank String batchNo,
        @NotNull Integer quantity
) {}
