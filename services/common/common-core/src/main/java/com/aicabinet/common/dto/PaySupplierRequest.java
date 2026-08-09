package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotNull;

public record PaySupplierRequest(
        @NotNull Long amountCents,
        String notes
) {}
