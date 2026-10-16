package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 商户端自助改密（CB-050 扩展）：须携带当前密码；新密码 6-64 位且不得与旧密码相同。 */
public record MerchantPasswordChangeRequest(
        @NotBlank String oldPassword,
        @NotBlank @Size(min = 6, max = 64, message = "新密码长度须为 6-64 位") String newPassword
) {}
