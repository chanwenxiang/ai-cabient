package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 编辑运营账号资料；改密请走独立重置密码接口。 */
public record UpdateOpsOperatorRequest(
        @NotBlank(message = "手机号不能为空")
        @Size(max = 32)
        String phoneNumber,
        @NotBlank(message = "姓名不能为空")
        @Size(max = 64)
        String name,
        String status,
        List<Long> deptIds,
        Long primaryDeptId
) {}
