package com.aicabinet.common.dto;

public record SlaRealtimeDto(
        double doorSuccessRate24h,
        long avgRecognizeMs24h,
        double deviceOnlineRateNow
) {}
