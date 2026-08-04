package com.aicabinet.common.dto;

import java.util.List;

public record OpsUserRolesDto(
        Long userId,
        List<Long> roleIds,
        List<String> permCodes
) {}
