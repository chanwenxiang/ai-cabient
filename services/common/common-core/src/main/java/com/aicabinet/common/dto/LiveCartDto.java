package com.aicabinet.common.dto;

import java.util.List;

/** 开门中实时购物车快照（第三方识别推送）。 */
public record LiveCartDto(
        String sessionId,
        List<LiveCartLine> items,
        int totalQty,
        int totalAmountCents
) {
    public record LiveCartLine(
            String skuId,
            String skuName,
            int quantity,
            int unitPriceCents,
            int lineAmountCents
    ) {}
}
