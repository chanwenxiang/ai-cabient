package com.aicabinet.common.dto;

import java.util.List;

public record OpsDepartmentMembersDto(
        Long deptId,
        String deptKey,
        String deptName,
        List<Long> userIds,
        List<String> userNames
) {}
