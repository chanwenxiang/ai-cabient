package com.aicabinet.common.dto;

import java.util.List;

public record CreateMerchantReplenishmentRequest(
        String deviceId,
        String notes,
        List<Line> lines
) {
    public record Line(String skuId, Integer requestedQty) {}
}
