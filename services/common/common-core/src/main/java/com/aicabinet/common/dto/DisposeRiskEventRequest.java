package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 风控事件人工确认处置请求。 */
public record DisposeRiskEventRequest(
        @NotBlank(message = "处置备注不能为空")
        @Size(max = 256, message = "处置备注不能超过 256 个字符")
        String note
) {}
