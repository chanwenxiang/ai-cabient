package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

public record MerchantAiInsightDto(
        String source,
        String model,
        String insight,
        Instant generatedAt,
        List<MerchantSkuPerformanceDto> skuPerformance
) {}
