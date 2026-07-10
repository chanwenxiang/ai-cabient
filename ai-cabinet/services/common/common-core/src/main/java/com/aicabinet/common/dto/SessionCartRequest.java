package com.aicabinet.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** 消费者演示购物车：关门结算时优先按此列表扣款（开发 mock 模式）。 */
public record SessionCartRequest(
        @Valid List<CartItem> items
) {
    public record CartItem(
            @NotBlank String skuId,
            @Min(0) int qty
    ) {}
}
