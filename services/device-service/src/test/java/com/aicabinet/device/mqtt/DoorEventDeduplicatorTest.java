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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoorEventDeduplicatorTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    /** M11：seen 只读不写键——未 mark 的事件可反复处理（crash 窗口不丢）。 */
    @Test
    void seen_isFalseUntilMarked() {
        DoorEventDeduplicator deduplicator = new DoorEventDeduplicator();
        assertFalse(deduplicator.seen("S1", "OPEN", "seq:1"));
        assertFalse(deduplicator.seen("S1", "OPEN", "seq:1"), "未 mark 前重投可再处理");

        deduplicator.mark("S1", "OPEN", "seq:1");

        assertTrue(deduplicator.seen("S1", "OPEN", "seq:1"));
        assertFalse(deduplicator.seen("S1", "OPEN", "seq:2"), "不同 fingerprint 不去重");
    }

    @Test
    void differentSessions_notDuplicate() {
        DoorEventDeduplicator deduplicator = new DoorEventDeduplicator();
        deduplicator.mark("S1", "OPEN", "");
        assertFalse(deduplicator.seen("S2", "OPEN", ""));
        assertTrue(deduplicator.seen("S1", "OPEN", ""));
    }

    /** M11：转发失败不 mark → 重投后 seen=false 可再次转发。 */
    @Test
    void failureWithoutMark_allowsRetryForward() {
        DoorEventDeduplicator deduplicator = new DoorEventDeduplicator();
        assertFalse(deduplicator.seen("S1", "CLOSED", "seq:1"));
        assertFalse(deduplicator.seen("S1", "CLOSED", "seq:1"));
        deduplicator.mark("S1", "CLOSED", "seq:1");
        assertTrue(deduplicator.seen("S1", "CLOSED", "seq:1"));
    }

    /** M11/Q7：Redis seen 只读（hasKey）、mark 写入（SET + TTL 60s），跨实例共享。 */
    @Test
    void redisSeen_falseUntilMark_secondInstanceBlocked() {
        Map<String, String> store = new ConcurrentHashMap<>();
        when(redis.hasKey(anyString())).thenAnswer(inv -> store.containsKey(inv.getArgument(0)));
        when(redis.opsForValue()).thenReturn(valueOps);
        doAnswer(inv -> {
            store.put(inv.getArgument(0), "1");
            return null;
        }).when(valueOps).set(anyString(), eq("1"), any(Duration.class));

        DoorEventDeduplicator a = new DoorEventDeduplicator(redis);
        DoorEventDeduplicator b = new DoorEventDeduplicator(redis);

        assertFalse(a.seen("S-Q7", "CLOSED", "seq:1"));
        a.mark("S-Q7", "CLOSED", "seq:1");

        assertTrue(b.seen("S-Q7", "CLOSED", "seq:1"));
        verify(valueOps, times(1)).set(
                eq("aicabinet:door-dedup:S-Q7:CLOSED:seq:1"),
                eq("1"),
                eq(Duration.ofMillis(60_000)));
        verify(redis, times(2)).hasKey("aicabinet:door-dedup:S-Q7:CLOSED:seq:1");
    }

    /** Redis 异常时 seen/mark 均回退本地，行为与本地模式一致。 */
    @Test
    void redisFailure_fallsBackToLocalDedup() {
        when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        DoorEventDeduplicator deduplicator = new DoorEventDeduplicator(redis);
        assertFalse(deduplicator.seen("S-R", "OPEN", "seq:1"));
        deduplicator.mark("S-R", "OPEN", "seq:1");
        assertTrue(deduplicator.seen("S-R", "OPEN", "seq:1"));
    }
}
