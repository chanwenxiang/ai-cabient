package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;

public record UpsertMerchantOnboardingRequest(
        @NotBlank String merchantId,
        @NotBlank String channel,
        String status,
        String externalMchId,
        String externalRef,
        String note
) {}
