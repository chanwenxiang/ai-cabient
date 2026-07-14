package com.aicabinet.common.dto;

import java.time.Instant;

public record DisputeMessageDto(
        Long messageId,
        String authorType,
        Long authorId,
        String authorName,
        String body,
        Instant createdAt
) {}
