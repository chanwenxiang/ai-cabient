package com.aicabinet.common.dto;

import java.util.List;

public record OrgNodeDto(
        Long nodeId,
        Long parentId,
        String name,
        String nodeType,
        int sortOrder,
        boolean enabled,
        List<String> deviceIds,
        List<OrgNodeDto> children
) {}
