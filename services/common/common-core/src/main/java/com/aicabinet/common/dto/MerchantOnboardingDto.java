package com.aicabinet.common.dto;

import java.time.Instant;

public record MerchantOnboardingDto(
        Long onboardingId,
        String merchantId,
        String merchantName,
        String subjectType,
        String alipayRegStatus,
        String wechatPayscoreStatus,
        String onboardStatus,
        String externalMerchantNo,
        String remark,
        Instant createdAt,
        Instant updatedAt
) {}
