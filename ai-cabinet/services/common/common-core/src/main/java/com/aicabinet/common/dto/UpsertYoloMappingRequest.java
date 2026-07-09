package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpsertYoloMappingRequest(
        @NotBlank String className,
        @NotBlank String skuId,
        @NotNull Float minConfidence,
        String mappingSource
) {}
