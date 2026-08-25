package com.aicabinet.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 第三方识别服务推送的开门中实时购物车（仅展示，不单独扣款）。
 * <ul>
 *   <li>{@code REPLACE} — 全量覆盖</li>
 *   <li>{@code DELTA} — 按 quantity 增量（正=拿取，负=放回）</li>
 * </ul>
 */
public record LiveCartUpdateRequest(
        String mode,
        @Valid List<LiveCartItem> items
) {
    public record LiveCartItem(
            @NotBlank String skuId,
            String skuName,
            int quantity,
            Integer unitPriceCents
    ) {}

    public String resolvedMode() {
        if (mode == null || mode.isBlank()) {
            return "REPLACE";
        }
        return mode.trim().toUpperCase();
    }
}
