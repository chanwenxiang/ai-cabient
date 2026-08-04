package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
