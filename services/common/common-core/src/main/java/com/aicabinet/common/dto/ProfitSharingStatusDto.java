package com.aicabinet.common.dto;

public record ProfitSharingStatusDto(
        boolean enabled,
        boolean apiReady,
        boolean retryEnabled,
        int retryBatchSize,
        String wechatPayConfigured,
        String note
) {}
