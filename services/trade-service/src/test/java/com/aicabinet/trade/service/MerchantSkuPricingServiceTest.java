package com.aicabinet.trade.service;

import com.aicabinet.trade.support.ApiMessages;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantSkuPricingServiceTest {

    @Test
    void priceHistoryTargetMatches_requiresExactDeviceId() {
        Set<String> allowed = Set.of("CAB-1");

        assertTrue(MerchantSkuPricingService.priceHistoryTargetMatches(
                "CAB-1:SKU-A", "CAB-1", null, allowed));
        assertFalse(MerchantSkuPricingService.priceHistoryTargetMatches(
                "CAB-10:SKU-B", "CAB-1", null, allowed));
    }

    @Test
    void priceHistoryTargetMatches_enforcesTenantScopeAndSkuFilter() {
        Set<String> allowed = Set.of("CAB-1", "CAB-2");

        assertTrue(MerchantSkuPricingService.priceHistoryTargetMatches(
                "CAB-2:SKU-A", null, "SKU-A", allowed));
        assertFalse(MerchantSkuPricingService.priceHistoryTargetMatches(
                "CAB-OTHER:SKU-A", null, "SKU-A", allowed));
        assertFalse(MerchantSkuPricingService.priceHistoryTargetMatches(
                "CAB-2:SKU-B", null, "SKU-A", allowed));
        assertFalse(MerchantSkuPricingService.priceHistoryTargetMatches(
                "invalid-target", null, null, allowed));
    }

    @Test
    void requireMatchingExpectedVersion_rejectsNullAndMismatch() {
        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> MerchantSkuPricingService.requireMatchingExpectedVersion(null, 3L));
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        assertEquals(ApiMessages.EXPECTED_VERSION_REQUIRED, missing.getReason());

        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                () -> MerchantSkuPricingService.requireMatchingExpectedVersion(2L, 3L));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals(ApiMessages.OPTIMISTIC_LOCK_CONFLICT, conflict.getReason());

        MerchantSkuPricingService.requireMatchingExpectedVersion(3L, 3L);
    }

    @Test
    void displayPriceHistoryDetail_translatesAuditEnglish() {
        assertEquals("覆盖价 无 → ¥3.60（基准 ¥3.50）",
                MerchantSkuPricingService.displayPriceHistoryDetail("base=350 override null -> 360"));
        assertEquals("覆盖价 ¥3.55 → ¥3.50（基准 ¥3.50）",
                MerchantSkuPricingService.displayPriceHistoryDetail("base=350 override 355 -> 350"));
        assertEquals("取消覆盖，恢复基准 ¥3.50（原覆盖 ¥3.60）",
                MerchantSkuPricingService.displayPriceHistoryDetail("reset to base 350 (was override 360)"));
        assertEquals("覆盖价 无 → ¥1.50（基准 ¥2.00）",
                MerchantSkuPricingService.formatPriceChangeAuditDetail(200, null, 150));
    }
}
