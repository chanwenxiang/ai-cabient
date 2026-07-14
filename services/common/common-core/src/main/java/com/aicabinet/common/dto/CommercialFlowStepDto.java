package com.aicabinet.common.dto;

import java.time.Instant;

public record CommercialFlowStepDto(
        String code,
        String status,
        String message,
        Instant at
) {}
