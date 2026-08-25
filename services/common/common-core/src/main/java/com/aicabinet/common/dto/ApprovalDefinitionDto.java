package com.aicabinet.common.dto;

import java.util.List;

public record ApprovalDefinitionDto(
        Long defId,
        String bizType,
        String defName,
        boolean enabled,
        String remark,
        List<ApprovalNodeDto> nodes
) {}
