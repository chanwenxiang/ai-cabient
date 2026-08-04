package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateOpsPermissionRequest(
        Long parentId,
        @NotBlank(message = "权限名称不能为空")
        @Size(max = 64)
        String permName,
        @NotBlank(message = "权限类型不能为空")
        String permType,
        @Size(max = 128)
        String path,
        Integer sortOrder,
        String status
) {}
