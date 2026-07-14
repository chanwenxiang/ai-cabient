package com.aicabinet.common.dto;

import java.time.Instant;

public record UserBlacklistDto(
        Long userId,
        String reason,
        String source,
        Instant expiresAt,
        Instant createdAt
) {}
