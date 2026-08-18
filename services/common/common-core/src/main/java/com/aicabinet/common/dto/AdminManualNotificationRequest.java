package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 运营后台手动发送站内信。
 */
public record AdminManualNotificationRequest(
        @NotBlank String audience,
        Long userId,
        String merchantId,
        @NotBlank String title,
        @NotBlank String body,
        String bizType,
        String bizId
) {}
