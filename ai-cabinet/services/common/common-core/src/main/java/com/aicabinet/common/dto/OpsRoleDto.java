package com.aicabinet.common.dto;

import java.util.List;

public record OpsRoleDto(
        Long roleId,
        String roleKey,
        String roleName,
        String status,
        String remark,
        List<String> permissions
) {}
