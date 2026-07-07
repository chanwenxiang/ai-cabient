package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ResolveDisputeRequest(
        @NotEmpty List<ManualLineItem> items
) {
    public record ManualLineItem(
            @NotBlank String skuId,
            int quantity
    ) {}
}
