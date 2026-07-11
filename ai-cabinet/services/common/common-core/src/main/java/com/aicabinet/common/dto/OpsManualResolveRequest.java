package com.aicabinet.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record OpsManualResolveRequest(
        @NotBlank @Size(max = 16) String resolutionType,
        @Valid List<ResolveDisputeRequest.ManualLineItem> items,
        @NotBlank @Size(max = 128) String idempotencyKey,
        @NotBlank @Size(max = 500) String reason
) {
    public OpsManualResolveRequest {
        if (items == null) items = List.of();
    }
}
