package com.aicabinet.common.dto;

import java.time.Instant;

public record OpsDepartmentDto(
        Long deptId,
        String deptKey,
        String deptName,
        Long parentId,
        Integer sortOrder,
        String status,
        String remark,
        int memberCount,
        Instant updatedAt
) {}
