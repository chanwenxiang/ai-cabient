package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 绑定邀请人（营销 2）。
 *
 * <p>邀请码统一 8 位、字符集去掉易混的 0/O/1/I；此处只做格式初筛（大写化由服务端做，
 * 用户手输小写不该被拒），真正的有效性判定在 {@code MemberInviteService.bind}。
 */
public record BindInviteRequest(
        @NotBlank @Pattern(regexp = "^[0-9A-Za-z]{4,64}$", message = "邀请码格式不正确") String inviteCode
) {}
