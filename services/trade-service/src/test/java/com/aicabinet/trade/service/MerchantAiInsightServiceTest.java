package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantSkuPerformanceDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantAiInsightServiceTest {

    @Test
    void ruleInsightSummarizesPerformanceWhenOllamaIsUnavailable() {
        List<MerchantSkuPerformanceDto> rows = List.of(
                row("A", "BEST_SELLER"), row("B", "SLOW_MOVER"), row("C", "NO_SALES"));

        String result = MerchantAiInsightService.ruleInsight(rows);

        assertTrue(result.contains("畅销 1 个"));
        assertTrue(result.contains("慢销 1 个"));
        assertTrue(result.contains("无销量 1 个"));
    }

    private static MerchantSkuPerformanceDto row(String skuId, String level) {
        return new MerchantSkuPerformanceDto(
                skuId, skuId, 0, 0, 0, 0, 0, 0, null, level, "test");
    }
}
