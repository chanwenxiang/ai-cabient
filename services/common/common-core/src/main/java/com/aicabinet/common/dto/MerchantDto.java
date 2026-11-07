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
        boolean allowMerchantPricingEdit,
        boolean packFieldEnabled,
        boolean packBizEnabled,
        boolean packTeamEnabled,
        String parentMerchantId,
        /** MA-165：法人姓名（提现资质）。 */
        String legalPerson,
        /** MA-165：营业执照图片 URL（提现资质）。 */
        String businessLicenseUrl,
        Instant createdAt,
        Instant updatedAt
) {}
