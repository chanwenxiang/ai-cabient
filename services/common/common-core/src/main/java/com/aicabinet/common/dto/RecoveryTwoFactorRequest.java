package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record RecoveryTwoFactorRequest(
        @NotBlank String challengeToken,
        @NotBlank String recoveryCode
) {}
