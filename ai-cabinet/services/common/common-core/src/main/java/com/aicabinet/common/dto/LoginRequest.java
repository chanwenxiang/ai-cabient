package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String phoneNumber,
        @NotBlank String code,
        String wxCode
) {}
