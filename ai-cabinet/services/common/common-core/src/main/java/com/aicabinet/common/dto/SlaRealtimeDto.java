package com.aicabinet.common.dto;

public record SlaRealtimeDto(
        double doorSuccessRate24h,
        long avgRecognizeMs24h,
        double deviceOnlineRateNow,
        long disputeOpen,
        long disputeOverdue,
        long disputeResolved24h,
        double disputeSlaCompliance24h
) {}
