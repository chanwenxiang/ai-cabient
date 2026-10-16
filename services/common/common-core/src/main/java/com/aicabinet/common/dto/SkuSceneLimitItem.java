package com.aicabinet.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * 商品×场景上架限额条目（CB-049 扩展，2026-10-16）。
 * 一个 SKU 可对多个投放场景各配一个上限；柜内该场景下该 SKU 的上架合计不得大于 max_qty。
 */
public record SkuSceneLimitItem(
        @Pattern(regexp = "[A-Z][A-Z_]{1,31}", message = "sceneType must be UPPER_SNAKE dict key")
        String sceneType,
        @Min(value = 1, message = "场景上架上限必须 >= 1")
        @Max(value = 9999, message = "场景上架上限过大")
        Integer maxQty
) {}
