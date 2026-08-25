package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record UpsertOrgNodeRequest(
        Long nodeId,
        Long parentId,
        @NotBlank String name,
        String nodeType,
        int sortOrder
) {}
