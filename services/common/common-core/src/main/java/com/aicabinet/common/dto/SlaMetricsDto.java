package com.aicabinet.common.dto;

import java.time.LocalDate;

public record SlaMetricsDto(
        LocalDate snapshotDate,
        int doorOpenAttempts,
        int doorOpenSuccess,
        double doorSuccessRate,
        long avgRecognizeMs,
        long p95RecognizeMs,
        int deviceTotal,
        int deviceOnlinePeak,
        double deviceOnlineRate,
        SlaRealtimeDto realtime
) {}
