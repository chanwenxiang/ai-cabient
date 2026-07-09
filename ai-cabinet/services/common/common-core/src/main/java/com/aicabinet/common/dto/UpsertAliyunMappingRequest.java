package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpsertAliyunMappingRequest(
        @NotBlank String categoryId,
        String categoryName,
        @NotBlank String skuId,
        @NotNull Float minConfidence
) {}
