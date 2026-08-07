package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

/** 运营账号自助修改密码。 */
public record ChangePasswordRequest(
        @NotBlank(message = "原密码不能为空") String oldPassword,
        @NotBlank(message = "新密码不能为空") String newPassword
) {}
