package com.aicabinet.common.dto;

import java.time.Instant;

public record MerchantDto(
        String merchantId,
        String merchantName,
        String contactPhone,
        int platformRateBps,
        String wechatReceiverId,
        String status,
        String remark,
        long deviceCount,
        Instant createdAt,
        Instant updatedAt
) {}
