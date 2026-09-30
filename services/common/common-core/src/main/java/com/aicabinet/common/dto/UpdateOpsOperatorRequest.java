package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 编辑运营账号资料；改密请走独立重置密码接口。
 * <p>C14：换绑手机号需提供发往新号码的短信验证码（phoneSmsCode），手机号未变更时可不传。</p>
 */
public record UpdateOpsOperatorRequest(
        // 可空：编辑弹窗里列表给的是脱敏手机号，留空=保持原号（换绑才填新号并带 phoneSmsCode）
        @Size(max = 32)
        String phoneNumber,
        @NotBlank(message = "姓名不能为空")
        @Size(max = 64)
        String name,
        String status,
        List<Long> deptIds,
        Long primaryDeptId,
        String phoneSmsCode
) {}
