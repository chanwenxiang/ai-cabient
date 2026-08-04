package com.aicabinet.common.dto;

import java.util.List;

public record OpsRolePermissionsDto(
        Long roleId,
        String roleKey,
        String roleName,
        List<Long> permissionIds
) {}
