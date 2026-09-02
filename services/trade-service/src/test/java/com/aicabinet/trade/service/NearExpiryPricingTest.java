package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearExpiryPricingTest {

    @Test
    void isNearExpiryByDate_withinWindow_isTrue() {
        LocalDate today = LocalDate.of(2026, 9, 2);
        assertTrue(InventoryLotService.isNearExpiryByDate(today.plusDays(3), 7, today));
        assertTrue(InventoryLotService.isNearExpiryByDate(today, 7, today));
    }

    @Test
    void isNearExpiryByDate_outsideOrExpired_isFalse() {
        LocalDate today = LocalDate.of(2026, 9, 2);
        assertFalse(InventoryLotService.isNearExpiryByDate(today.plusDays(8), 7, today));
        assertFalse(InventoryLotService.isNearExpiryByDate(today.minusDays(1), 7, today));
        assertFalse(InventoryLotService.isNearExpiryByDate(null, 7, today));
    }

    @Test
    void pickUnitPrice_usesNearExpiryWhenActive() {
        assertEquals(299, MerchantSkuPricingService.pickUnitPriceCents(500, 299, true));
        assertEquals(500, MerchantSkuPricingService.pickUnitPriceCents(500, 299, false));
        assertEquals(500, MerchantSkuPricingService.pickUnitPriceCents(500, null, true));
        assertEquals(500, MerchantSkuPricingService.pickUnitPriceCents(500, 0, true));
    }
}
