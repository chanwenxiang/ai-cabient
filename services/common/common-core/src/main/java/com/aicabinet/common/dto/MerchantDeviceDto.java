package com.aicabinet.common.dto;

import java.time.Instant;

public record MerchantDeviceDto(
        String deviceId,
        String deviceName,
        String deviceType,
        String onlineStatus,
        String merchantId,
        String merchantName,
        String activeSessionId,
        String activeSessionState,
        Instant updatedAt,
        boolean replenishmentInProgress
) {}
