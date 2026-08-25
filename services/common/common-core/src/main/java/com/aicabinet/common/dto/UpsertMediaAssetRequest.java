package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record UpsertMediaAssetRequest(
        @NotBlank String title,
        int durationSeconds,
        String status
) {}
