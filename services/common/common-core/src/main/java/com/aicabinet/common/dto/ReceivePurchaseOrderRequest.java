package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record ReceivePurchaseOrderRequest(
        @NotEmpty List<PurchaseOrderLineDto> lines,
        String notes
) {}
