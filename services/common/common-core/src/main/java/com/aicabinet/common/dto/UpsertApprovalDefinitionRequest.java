package com.aicabinet.common.dto;

import java.util.List;

public record UpsertApprovalDefinitionRequest(
        String defName,
        Boolean enabled,
        String remark,
        List<ApprovalNodeDto> nodes
) {}
