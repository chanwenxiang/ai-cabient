package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyTwoFactorRequest(
        @NotBlank String challengeToken,
        @NotBlank String code
) {}
