package com.aicabinet.common.dto;

import java.time.Instant;

public record UserFeedbackDto(
        Long feedbackId,
        Long userId,
        String feedbackType,
        String content,
        String contactInfo,
        String deviceId,
        String sessionId,
        Integer rating,
        String status,
        Long handlerId,
        String reply,
        Instant handledAt,
        Instant createdAt
) {}
