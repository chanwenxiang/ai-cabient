package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新建审批流定义。
 */
public record CreateApprovalDefinitionRequest(
        @NotBlank @Size(max = 32) String bizType,
        @NotBlank @Size(max = 64) String defName,
        Boolean enabled,
        @Size(max = 256) String remark,
        List<ApprovalNodeDto> nodes
) {}
