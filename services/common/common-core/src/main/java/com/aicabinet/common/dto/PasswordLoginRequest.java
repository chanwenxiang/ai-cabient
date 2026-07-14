package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record PasswordLoginRequest(
        @NotBlank(message = "手机号不能为空") String phoneNumber,
        @NotBlank(message = "密码不能为空") String password
) {}
