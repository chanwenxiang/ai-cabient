package com.aicabinet.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreatePurchaseReturnRequest(
        @NotNull Long purchaseOrderId,
        String notes,
        @NotEmpty @Valid List<PurchaseReturnLineRequest> lines
) {
    public record PurchaseReturnLineRequest(
            @NotNull Long purchaseLineId,
            int quantity
    ) {}
}
