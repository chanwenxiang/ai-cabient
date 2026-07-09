package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record CreatePurchaseOrderRequest(
        @NotBlank String supplierId,
        String warehouseId,
        String refNo,
        String notes,
        @NotEmpty List<PurchaseOrderLineDto> lines
) {}
