package com.aicabinet.common.dto;

import com.fasterxml.jackson.annotation.JsonView;

/** 订单行；字段挂 {@link OrderViews.Public}，避免详情 @JsonView 裁剪时变成空对象。 */
public record OrderLineDto(
        @JsonView(OrderViews.Public.class) String skuId,
        @JsonView(OrderViews.Public.class) String skuName,
        @JsonView(OrderViews.Public.class) int quantity,
        @JsonView(OrderViews.Public.class) int unitPriceCents,
        @JsonView(OrderViews.Public.class) int lineAmountCents,
        @JsonView(OrderViews.Public.class) String batchNo,
        @JsonView(OrderViews.Public.class) String slotId
) {
    public OrderLineDto(String skuId, String skuName, int quantity, int unitPriceCents, int lineAmountCents) {
        this(skuId, skuName, quantity, unitPriceCents, lineAmountCents, null, null);
    }

    public OrderLineDto(String skuId, String skuName, int quantity, int unitPriceCents, int lineAmountCents,
                        String batchNo) {
        this(skuId, skuName, quantity, unitPriceCents, lineAmountCents, batchNo, null);
    }
}
