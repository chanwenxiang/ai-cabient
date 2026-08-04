package com.aicabinet.common.dto;

public record AdminOpsDailyDto(
        String date,
        long completedSessions,
        long disputedSessions,
        double recognitionRate,
        double disputeRate
) {}
