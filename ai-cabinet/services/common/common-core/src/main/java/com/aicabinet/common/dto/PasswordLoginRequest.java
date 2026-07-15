package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record PasswordLoginRequest(
        @NotBlank String phoneNumber,
        @NotBlank String password
) {}
