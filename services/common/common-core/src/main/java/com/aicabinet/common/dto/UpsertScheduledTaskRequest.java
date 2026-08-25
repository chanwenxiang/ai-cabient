package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建或更新定时任务元数据（启停请用 enabled 接口）。
 */
public record UpsertScheduledTaskRequest(
        @NotBlank @Size(max = 64) String taskKey,
        @NotBlank @Size(max = 128) String taskName,
        @NotBlank @Size(max = 32) String taskGroup,
        @Size(max = 255) String scheduleDesc,
        Boolean enabled,
        @Size(max = 500) String remark
) {}
