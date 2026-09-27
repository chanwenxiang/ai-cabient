package com.aicabinet.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record UpsertMerchantRequest(
        /** 新建时留空由系统发 12 位号；编辑时必填已有编号。手填新号将被拒绝。 */
        String merchantId,
        @NotBlank String merchantName,
        String contactPhone,
        @Min(0) @Max(10000) Integer platformRateBps,
        String wechatReceiverId,
        String status,
        String remark,
        Boolean allowMerchantPlanogramEdit,
        Boolean allowMerchantPricingEdit,
        Boolean packFieldEnabled,
        Boolean packBizEnabled,
        Boolean packTeamEnabled,
        String parentMerchantId
) {}
