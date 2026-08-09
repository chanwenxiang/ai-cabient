package com.aicabinet.common.dto;

import java.time.Instant;

public record MemberPointsLogDto(
        Long id,
        int points,
        String pointsType,
        String sourceType,
        String description,
        Instant createdAt,
        Instant expireAt
) {}
