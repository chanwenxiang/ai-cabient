package com.aicabinet.common.dto;

import java.time.Instant;

public record MerchantDto(
        String merchantId,
        String merchantName,
        String contactPhone,
        String alertContactName,
        String alertContactPhone,
        int platformRateBps,
        String wechatReceiverId,
        String status,
        String remark,
        long deviceCount,
        boolean allowMerchantPlanogramEdit,
        boolean allowMerchantPricingEdit,
        boolean packFieldEnabled,
        boolean packBizEnabled,
        boolean packTeamEnabled,
        String parentMerchantId,
        Instant createdAt,
        Instant updatedAt
) {}
