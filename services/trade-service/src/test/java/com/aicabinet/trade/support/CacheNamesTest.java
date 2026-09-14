package com.aicabinet.trade.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheNamesTest {

    @Test
    void ttlBands_areOrderedAndStable() {
        assertEquals(30_000L, CacheNames.TTL_SHORT_MS);
        assertEquals(60_000L, CacheNames.TTL_MEDIUM_MS);
        assertEquals(300_000L, CacheNames.TTL_DEFAULT_MS);
        assertTrue(CacheNames.TTL_SHORT_MS < CacheNames.TTL_MEDIUM_MS);
        assertTrue(CacheNames.TTL_MEDIUM_MS < CacheNames.TTL_DEFAULT_MS);
    }

    @Test
    void dashboardPrefixes_useStableNames() {
        assertEquals("dashboard:stats", CacheNames.DASHBOARD_STATS);
        assertEquals("admin:skus", CacheNames.ADMIN_SKUS);
        assertEquals("admin:devices:ref", CacheNames.ADMIN_DEVICES_REF);
    }
}
