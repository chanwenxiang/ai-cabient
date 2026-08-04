package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateOpsOperatorRequest(
        @NotBlank(message = "手机号不能为空")
        @Size(max = 32)
        String phoneNumber,
        @NotBlank(message = "姓名不能为空")
        @Size(max = 64)
        String name,
        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 64)
        String password,
        String status,
        List<Long> roleIds
) {}
