package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record WxLoginRequest(
        @NotBlank String code,
        String phoneNumber
) {}
