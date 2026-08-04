package com.aicabinet.common.dto;

import java.time.Instant;

public record AnnouncementDto(
        Long announceId,
        String title,
        String content,
        String announceType,
        String targetScope,
        String priority,
        Instant publishAt,
        Instant expireAt
) {}
