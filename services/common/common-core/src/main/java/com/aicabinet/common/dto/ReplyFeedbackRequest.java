package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReplyFeedbackRequest(
        @NotBlank(message = "回复内容不能为空")
        @Size(max = 2000)
        String reply
) {}
