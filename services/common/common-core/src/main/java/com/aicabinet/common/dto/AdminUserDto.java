package com.aicabinet.common.dto;

import java.time.Instant;

public record AdminUserDto(
        Long userId,
        String phoneNumber,
        String name,
        String nickname,
        boolean verified,
        int balanceCents,
        String role,
        Instant createdAt,
        String memberLevel,
        int availablePoints,
        boolean blacklisted
) {}
