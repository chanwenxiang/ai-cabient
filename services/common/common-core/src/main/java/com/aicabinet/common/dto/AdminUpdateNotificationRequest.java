package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 运营后台修正已发送站内信的标题/正文。
 */
public record AdminUpdateNotificationRequest(
        @NotBlank String title,
        @NotBlank String body
) {}
