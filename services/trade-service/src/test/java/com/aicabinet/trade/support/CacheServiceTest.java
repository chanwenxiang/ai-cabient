package com.aicabinet.trade.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CacheServiceTest {

    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        cacheService = new CacheService(provider, new ObjectMapper());
    }

    @Test
    void cachesNullPlaceholder_avoidsRepeatedLoader() {
        AtomicInteger loads = new AtomicInteger();
        assertNull(cacheService.get("t", "k", 60_000L, () -> {
            loads.incrementAndGet();
            return null;
        }));
        assertNull(cacheService.get("t", "k", 60_000L, () -> {
            loads.incrementAndGet();
            return null;
        }));
        assertEquals(1, loads.get());
        assertEquals(1, cacheService.hits("t"));
        assertEquals(1, cacheService.misses("t"));
    }

    @Test
    void cachesValue() {
        AtomicInteger loads = new AtomicInteger();
        assertEquals("v1", cacheService.get("t", "a", () -> {
            loads.incrementAndGet();
            return "v1";
        }));
        assertEquals("v1", cacheService.get("t", "a", () -> {
            loads.incrementAndGet();
            return "v2";
        }));
        assertEquals(1, loads.get());
    }
}
