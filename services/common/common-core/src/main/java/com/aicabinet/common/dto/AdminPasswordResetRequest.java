package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

/** 运营后台忘记密码：短信验证码 + 图形验证码重置。 */
public record AdminPasswordResetRequest(
        @NotBlank(message = "手机号不能为空") String phoneNumber,
        @NotBlank(message = "短信验证码不能为空") String smsCode,
        String captchaId,
        String captchaCode,
        @NotBlank(message = "新密码不能为空") String newPassword
) {}
