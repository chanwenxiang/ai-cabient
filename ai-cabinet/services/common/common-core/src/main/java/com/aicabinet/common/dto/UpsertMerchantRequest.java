package com.aicabinet.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record UpsertMerchantRequest(
        @NotBlank String merchantId,
        @NotBlank String merchantName,
        String contactPhone,
        @Min(0) @Max(10000) Integer platformRateBps,
        String wechatReceiverId,
        String status,
        String remark
) {}
