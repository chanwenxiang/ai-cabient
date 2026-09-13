package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 运营后台重置他人账号密码（禁止重置自己）。 */
public record ResetOpsOperatorPasswordRequest(
        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 64, message = "新密码长度需在 6-64 位之间")
        String password
) {}
