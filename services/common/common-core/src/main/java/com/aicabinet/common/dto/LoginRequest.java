package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "手机号不能为空") String phoneNumber,
        @NotBlank(message = "验证码不能为空") String code,
        String wxCode
) {}
