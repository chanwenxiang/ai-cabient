package com.aicabinet.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StocktakeAdjustRequest(
        @NotBlank String deviceId,
        @NotBlank String skuId,
        @NotNull @Min(0) Integer countedQuantity,
        String note,
        /** 盘点照片凭证；商户开启 photoStocktake 时必填 */
        String photoEvidenceUrl,
        /**
         * 客户端看到的库存版本。
         * <p><b>破坏性契约（2026-09-15）</b>：目标 SKU 在 {@code device_sku_inventory} 已有行时必填；
         * 缺失 → HTTP 400；与当前行不一致 → HTTP 409「他人已修改，请刷新」。
         * 新建库存行时可省略。调用方须先读列表/详情中的 {@code inventoryVersion}。
         */
        Long expectedVersion
) {}
