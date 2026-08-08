package com.aicabinet.common.dto;

public record LoginResponse(
        String token,
        Long userId,
        long expiresInSeconds,
        long serverBootEpoch,
        boolean cookieEnabled
) {}
