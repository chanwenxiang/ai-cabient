package com.aicabinet.common.dto;

import java.time.Instant;

public record MerchantPaymentOnboardingDto(
        Long onboardingId,
        String merchantId,
        String merchantName,
        String channel,
        String status,
        String externalMchId,
        String externalRef,
        String note,
        Instant lastSyncedAt,
        Instant createdAt,
        Instant updatedAt,
        boolean payLiveHint
) {}
