package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record TwoFactorCodeRequest(
        @NotBlank String code
) {}
