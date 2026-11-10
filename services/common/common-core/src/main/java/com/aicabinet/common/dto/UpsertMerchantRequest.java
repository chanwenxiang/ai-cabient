package com.aicabinet.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertMerchantRequest(
        /** 新建时留空由系统发 12 位号；编辑时必填已有编号。手填新号将被拒绝。 */
        String merchantId,
        @NotBlank String merchantName,
        String contactPhone,
        @Min(0) @Max(10000) Integer platformRateBps,
        String wechatReceiverId,
        String status,
        String remark,
        Boolean allowMerchantPricingEdit,
        Boolean packFieldEnabled,
        Boolean packBizEnabled,
        Boolean packTeamEnabled,
        String parentMerchantId,
        /** MA-165：法人姓名——商户提现资质校验（WithdrawEligibilityService）要求非空。 */
        @Size(max = 64) String legalPerson,
        /** MA-165：营业执照图片 URL——提现资质校验要求非空。 */
        @Size(max = 255) String businessLicenseUrl
) {}
