package com.aicabinet.common.dto;

public record OrderLineDto(
        String skuId,
        String skuName,
        int quantity,
        int unitPriceCents,
        int lineAmountCents,
        String batchNo,
        String slotId
) {
    public OrderLineDto(String skuId, String skuName, int quantity, int unitPriceCents, int lineAmountCents) {
        this(skuId, skuName, quantity, unitPriceCents, lineAmountCents, null, null);
    }

    public OrderLineDto(String skuId, String skuName, int quantity, int unitPriceCents, int lineAmountCents,
                        String batchNo) {
        this(skuId, skuName, quantity, unitPriceCents, lineAmountCents, batchNo, null);
    }
}
