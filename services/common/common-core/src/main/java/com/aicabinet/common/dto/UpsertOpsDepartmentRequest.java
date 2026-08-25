package com.aicabinet.common.dto;

public record UpsertOpsDepartmentRequest(
        String deptKey,
        String deptName,
        Long parentId,
        Integer sortOrder,
        String status,
        String remark
) {}
