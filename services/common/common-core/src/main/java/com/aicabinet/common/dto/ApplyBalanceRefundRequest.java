package com.aicabinet.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record ApplyBalanceRefundRequest(
        @Min(100) @Max(500_000) int amountCents,
        @Size(max = 200) String reason
) {}
