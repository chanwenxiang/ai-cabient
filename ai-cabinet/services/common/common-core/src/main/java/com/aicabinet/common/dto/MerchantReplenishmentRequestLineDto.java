package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

public record MerchantReplenishmentRequestLineDto(
        Long lineId,
        String skuId,
        String skuName,
        int suggestedQty,
        int requestedQty
) {}
