package com.aicabinet.common.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpsertSkuRequest(
        /** 内部主键；新建可空，服务端生成与 skuCode 相同的纯数字 ID */
        String skuId,
        @NotBlank String skuName,
        @Min(1) int priceCents,
        Integer weightGrams,
        Boolean visionEnabled,
        String imageUrl,
        String description,
        String category,
        String barcode,
        @Pattern(regexp = "ACTIVE|INACTIVE", message = "status must be ACTIVE or INACTIVE")
        String status,
        Integer shelfLifeDays,
        Integer nearExpiryDays,
        Integer blockSaleDaysBeforeExpiry,
        @Pattern(regexp = "AMBIENT|CHILLED|FROZEN", message = "storageType must be AMBIENT, CHILLED or FROZEN")
        String storageType,
        Integer purchaseCostCents,
        Integer nearExpiryPriceCents,
        @DecimalMin(value = "0.5", message = "minChargeConfidence must be >= 0.5")
        @DecimalMax(value = "1.0", message = "minChargeConfidence must be <= 1.0")
        Float minChargeConfidence,
        String yoloClassName,
        @Pattern(regexp = "DRAFT|MAPPING|TESTED|PRODUCTION", message = "invalid visionEnrollmentStatus")
        String visionEnrollmentStatus,
        @DecimalMin(value = "0.1", message = "detectionMinConfidence must be >= 0.1")
        @DecimalMax(value = "1.0", message = "detectionMinConfidence must be <= 1.0")
        Float detectionMinConfidence,
        String referenceImageUrlsJson,
        /** 客户端传入会被忽略；仅兼容字段 */
        Long skuCode,
        String brand,
        String spec,
        String unit,
        /** 件装数（CB-061）：每件的支数，用于采购按件、库存/销售按支的换算；默认 1 表示无箱规。 */
        int packQty,
        /** 经营归属（V349 字典 sku_operation_scope）；空=未分类，取值校验见 @Pattern */
        @Pattern(regexp = "DIRECT|NON_DIRECT", message = "operationScope must be DIRECT or NON_DIRECT")
        String operationScope,
        /** 最高限价（V350，CB-037）：null=默认基准价 2 倍 */
        Integer maxPriceCents,
        /** 最低限价（V350，CB-037）：null=不限（仅要求 >0）；用户否决"不低于成本"硬线，支持打折/清仓 */
        Integer minPriceCents,
        /**
         * 商品×场景上架限额（CB-049 扩展）：整单替换；商品必须至少配一个场景。
         * 同一场景重复出现判 400；上限 1..9999。
         */
        java.util.List<SkuSceneLimitItem> sceneLimits
) {
    public UpsertSkuRequest {
        if (visionEnabled == null) {
            visionEnabled = true;
        }
        if (status == null || status.isBlank()) {
            status = "ACTIVE";
        }
        if (nearExpiryDays == null) {
            nearExpiryDays = 7;
        }
        if (blockSaleDaysBeforeExpiry == null) {
            blockSaleDaysBeforeExpiry = 0;
        }
        if (storageType == null || storageType.isBlank()) {
            storageType = "AMBIENT";
        }
        if (unit == null || unit.isBlank()) {
            unit = "件";
        }
        if (packQty <= 0) {
            packQty = 1;
        }
        if (operationScope != null && operationScope.isBlank()) {
            operationScope = null;
        }
    }
}
