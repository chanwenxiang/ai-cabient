package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record AddBlacklistRequest(
        @NotNull Long userId,
        @NotBlank String reason,
        Instant expiresAt
) {}
