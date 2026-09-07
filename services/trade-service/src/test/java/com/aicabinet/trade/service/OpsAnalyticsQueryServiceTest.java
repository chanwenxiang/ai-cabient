package com.aicabinet.trade.service;

import com.aicabinet.common.dto.AdminOpsDailyDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpsAnalyticsQueryServiceTest {

    @Test
    void normalizeTrendDays_snapsTo7_30_90() {
        assertEquals(7, OpsAnalyticsQueryService.normalizeTrendDays(1));
        assertEquals(7, OpsAnalyticsQueryService.normalizeTrendDays(7));
        assertEquals(7, OpsAnalyticsQueryService.normalizeTrendDays(29));
        assertEquals(30, OpsAnalyticsQueryService.normalizeTrendDays(30));
        assertEquals(30, OpsAnalyticsQueryService.normalizeTrendDays(89));
        assertEquals(90, OpsAnalyticsQueryService.normalizeTrendDays(90));
        assertEquals(90, OpsAnalyticsQueryService.normalizeTrendDays(120));
    }

    @Test
    void toOpsDailyPoints_computesRates() {
        Map<LocalDate, long[]> buckets = new LinkedHashMap<>();
        buckets.put(LocalDate.of(2026, 9, 1), new long[]{8, 2});
        buckets.put(LocalDate.of(2026, 9, 2), new long[]{0, 0});

        List<AdminOpsDailyDto> points = OpsAnalyticsQueryService.toOpsDailyPoints(buckets);

        assertEquals(2, points.size());
        assertEquals(0.8, points.get(0).recognitionRate(), 1e-9);
        assertEquals(0.2, points.get(0).disputeRate(), 1e-9);
        assertEquals(1.0, points.get(1).recognitionRate(), 1e-9);
        assertEquals(0.0, points.get(1).disputeRate(), 1e-9);
    }
}
