package com.aicabinet.common.dto;

import java.time.Instant;

public record AdminUserDto(
        Long userId,
        String phoneNumber,
        String name,
        boolean verified,
        int balanceCents,
        String role,
        Instant createdAt
) {}
