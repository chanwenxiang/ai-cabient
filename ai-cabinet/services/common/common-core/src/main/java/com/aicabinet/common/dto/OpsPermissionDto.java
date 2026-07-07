package com.aicabinet.common.dto;

public record OpsPermissionDto(
        Long permissionId,
        Long parentId,
        String permCode,
        String permName,
        String permType,
        String path,
        int sortOrder
) {}
