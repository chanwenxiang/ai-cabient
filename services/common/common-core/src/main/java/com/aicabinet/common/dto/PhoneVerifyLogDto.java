package com.aicabinet.common.dto;

import java.time.Instant;

public record PhoneVerifyLogDto(
        Long logId,
        Long userId,
        String phone,
        String channel,
        String merchantId,
        Instant verifiedAt
) {}
