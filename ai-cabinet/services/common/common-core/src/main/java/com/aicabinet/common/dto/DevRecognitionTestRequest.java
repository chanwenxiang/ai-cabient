package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record DevRecognitionTestRequest(
        @NotBlank String deviceId,
        String sessionId,
        /** FULL=创建会话并模拟关门识别；CLOSE_ONLY=对已有会话模拟关门 */
        String mode
) {}
