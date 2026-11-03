package com.aicabinet.common.dto;

import jakarta.validation.constraints.Size;

import jakarta.validation.constraints.NotBlank;

public record UpsertMediaAssetRequest(
        @NotBlank @Size(max = 128) String title,
        int durationSeconds,
        String status
) {}
