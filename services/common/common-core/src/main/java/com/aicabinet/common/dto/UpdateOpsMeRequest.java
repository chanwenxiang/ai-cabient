package com.aicabinet.common.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 运营账号自助更新个人资料。
 * <p>C14：换绑手机号需提供发往新号码的短信验证码（phoneSmsCode），手机号未变更时可不传。</p>
 */
public record UpdateOpsMeRequest(
        @NotBlank(message = "手机号不能为空")
        @Size(max = 32)
        String phoneNumber,
        @NotBlank(message = "姓名不能为空")
        @Size(max = 64)
        String name,
        @Email(message = "邮箱格式不正确")
        @Size(max = 128)
        String email,
        @Size(max = 512)
        String avatarUrl,
        String phoneSmsCode
) {}
