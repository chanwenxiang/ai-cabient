package com.aicabinet.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.util.List;

public record CreateWarehouseTransferRequest(
        @NotBlank String fromWarehouseId,
        @NotBlank String toWarehouseId,
        String notes,
        @NotEmpty @Valid List<Line> lines
) {
    public record Line(
            @NotBlank String skuId,
            String batchNo,
            LocalDate expiryDate,
            @Positive int quantity
    ) {}
}
