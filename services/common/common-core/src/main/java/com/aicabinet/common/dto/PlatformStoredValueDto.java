package com.aicabinet.common.dto;

public record PlatformStoredValueDto(
        String merchantId,
        long balanceCents,
        long warnThresholdCents,
        String notifyPhone
) {}
