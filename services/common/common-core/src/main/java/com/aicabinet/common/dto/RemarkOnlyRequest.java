package com.aicabinet.common.dto;

import jakarta.validation.constraints.Size;

/** 仅备注的请求体（提现失败单撤销等）。 */
public record RemarkOnlyRequest(
        @Size(max = 200) String remark
) {}
