package com.aicabinet.device.mqtt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoorEventDeduplicatorTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    @Test
    void duplicateWithinWindow_isDetected() {
        DoorEventDeduplicator deduplicator = new DoorEventDeduplicator();
        assertFalse(deduplicator.isDuplicate("S1", "OPEN"));
        assertTrue(deduplicator.isDuplicate("S1", "OPEN"));
        assertFalse(deduplicator.isDuplicate("S1", "CLOSED"));
    }

    @Test
    void differentSessions_notDuplicate() {
        DoorEventDeduplicator deduplicator = new DoorEventDeduplicator();
        assertFalse(deduplicator.isDuplicate("S1", "OPEN"));
        assertFalse(deduplicator.isDuplicate("S2", "OPEN"));
    }

    @Test
    void clear_allowsRetryAfterFailure() {
        DoorEventDeduplicator deduplicator = new DoorEventDeduplicator();
        assertFalse(deduplicator.isDuplicate("S1", "CLOSED", "seq:1"));
        assertTrue(deduplicator.isDuplicate("S1", "CLOSED", "seq:1"));
        deduplicator.clear("S1", "CLOSED", "seq:1");
        assertFalse(deduplicator.isDuplicate("S1", "CLOSED", "seq:1"));
    }

    /** Q7：Redis SET NX 跨调用共享 — 模拟多实例同一 Redis。 */
    @Test
    void redisSetNx_secondCallIsDuplicate_withExpectedKeyAndTtl() {
        Map<String, String> store = new ConcurrentHashMap<>();
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return store.putIfAbsent(key, "1") == null;
        });

        DoorEventDeduplicator a = new DoorEventDeduplicator(redis);
        DoorEventDeduplicator b = new DoorEventDeduplicator(redis);

        assertFalse(a.isDuplicate("S-Q7", "CLOSED", "seq:1"));
        assertTrue(b.isDuplicate("S-Q7", "CLOSED", "seq:1"));

        verify(valueOps, times(2)).setIfAbsent(
                eq("aicabinet:door-dedup:S-Q7:CLOSED:seq:1"),
                eq("1"),
                eq(Duration.ofMillis(60_000)));
    }
}
