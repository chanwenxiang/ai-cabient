package com.aicabinet.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "库存盘点调整请求")
public record StocktakeAdjustRequest(
        @NotBlank
        @Schema(description = "设备 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        String deviceId,

        @NotBlank
        @Schema(description = "SKU ID", requiredMode = Schema.RequiredMode.REQUIRED)
        String skuId,

        @NotNull @Min(0)
        @Schema(description = "盘点后数量", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer countedQuantity,

        @Schema(description = "备注")
        String note,

        /** 盘点照片凭证；商户开启 photoStocktake 时必填 */
        @Schema(description = "盘点照片凭证 URL；商户开启 photoStocktake 时必填")
        String photoEvidenceUrl,

        /**
         * 客户端看到的库存版本。
         * <p><b>破坏性契约（2026-09-15）</b>：目标 SKU 在 {@code device_sku_inventory} 已有行时必填；
         * 缺失 → HTTP 400；与当前行不一致 → HTTP 409「他人已修改，请刷新」。
         * 新建库存行时可省略。调用方须先读列表/详情中的 {@code inventoryVersion}。
         */
        @Schema(description = "客户端看到的库存版本。已有库存行时必填：缺失返回 400，版本冲突返回 409。新建库存行时可省略。")
        Long expectedVersion
) {}
