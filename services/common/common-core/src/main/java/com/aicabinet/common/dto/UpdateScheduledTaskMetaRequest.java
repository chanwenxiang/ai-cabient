package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 更新定时任务展示元数据（不含 taskKey）。
 */
public record UpdateScheduledTaskMetaRequest(
        @NotBlank @Size(max = 128) String taskName,
        @NotBlank @Size(max = 32) String taskGroup,
        @Size(max = 255) String scheduleDesc,
        @Size(max = 500) String remark
) {}
