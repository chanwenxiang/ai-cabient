package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateOpsRoleRequest(
        @NotBlank(message = "角色名称不能为空")
        @Size(max = 64)
        String roleName,
        @Size(max = 255)
        String remark,
        String status
) {}
