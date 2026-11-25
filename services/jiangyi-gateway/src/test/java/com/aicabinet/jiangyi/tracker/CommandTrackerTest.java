package com.aicabinet.jiangyi.tracker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AU-061：tracker 面 orderNo 归属校验 + Redis key 字符集/长度约束。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommandTrackerTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ZSetOperations<String, String> zSet;
    @Mock private HashOperations<String, Object, Object> hash;

    private CommandTracker tracker;

    @BeforeEach
    void setUp() {
        when(redis.opsForZSet()).thenReturn(zSet);
        when(redis.opsForHash()).thenReturn(hash);
        tracker = new CommandTracker(redis);
    }

    @Test
    void complete_openByForeignDevice_cannotRevokeVictimPending() {
        when(hash.get("jiangyi:cmd:open:1234567890123123456789", "deviceId")).thenReturn("D-VICTIM");

        boolean acked = tracker.complete(CommandTracker.Kind.OPEN, "1234567890123123456789", "D-ATTACKER");

        assertFalse(acked);
        verify(zSet, never()).remove(anyString(), eq("1234567890123123456789"));
        verify(hash, never()).put(anyString(), eq("status"), eq("ACKED"));
    }

    @Test
    void complete_withoutPendingRegistration_doesNotCreateOrphanHash() {
        when(hash.get("jiangyi:cmd:close:9876543210987654321098", "deviceId")).thenReturn(null);

        boolean acked = tracker.complete(CommandTracker.Kind.CLOSE, "9876543210987654321098", "D-1");

        assertFalse(acked);
        verify(hash, never()).put(anyString(), anyString(), any());
        verify(zSet, never()).remove(anyString(), anyString());
    }

    @Test
    void complete_byRegisteredOwner_succeeds() {
        when(hash.get("jiangyi:cmd:open:1112223334445556667778", "deviceId")).thenReturn("D-1");

        boolean acked = tracker.complete(CommandTracker.Kind.OPEN, "1112223334445556667778", "D-1");

        assertTrue(acked);
        verify(zSet).remove("jiangyi:cmd:open:pending", "1112223334445556667778");
        verify(hash).put("jiangyi:cmd:open:1112223334445556667778", "status", "ACKED");
    }

    @Test
    void register_unsafeKeyChars_skipsRedisWrites() {
        // 固件可传任意串：含空格/冒号/换行的 orderNo 不得进键空间
        tracker.register(CommandTracker.Kind.CLOSE, "evil key:jiangyi:cmd:x\n", "D-1", "id-1");

        verify(zSet, never()).add(anyString(), anyString(), anyDouble());
        verify(hash, never()).putAll(anyString(), any(Map.class));
    }

    @Test
    void register_over64Chars_skipsRedisWrites() {
        tracker.register(CommandTracker.Kind.CLOSE, "7".repeat(65), "D-1", "id-1");

        verify(zSet, never()).add(anyString(), anyString(), anyDouble());
        verify(hash, never()).putAll(anyString(), any(Map.class));
    }

    @Test
    void register_normalNumericSessionId_writesPending() {
        tracker.register(CommandTracker.Kind.CLOSE, "1728000000000123456789", "D-1", "id-1");

        verify(zSet).add(eq("jiangyi:cmd:close:pending"), eq("1728000000000123456789"), anyDouble());
        verify(hash).putAll(eq("jiangyi:cmd:close:1728000000000123456789"), any(Map.class));
    }

    @Test
    void complete_unsafeKey_skipsRedisWrites() {
        assertFalse(tracker.complete(CommandTracker.Kind.CLOSE, "a".repeat(100), "D-1"));
        verify(zSet, never()).remove(anyString(), anyString());
    }

    @Test
    void registerModel_usesAtomicDeviceNxRegistration() {
        when(redis.execute(org.mockito.ArgumentMatchers.any(
                        org.springframework.data.redis.core.script.RedisScript.class),
                eq(List.of(CommandTracker.modelDeviceKey("D-1"), "jiangyi:cmd:model:pending",
                        "jiangyi:cmd:model:9")),
                eq("9"), eq("D-1"), eq("id-1"), anyString(), anyString())).thenReturn(1L);

        assertTrue(tracker.register(CommandTracker.Kind.MODEL, "9", "D-1", "id-1"));

        verify(redis).execute(org.mockito.ArgumentMatchers.any(
                        org.springframework.data.redis.core.script.RedisScript.class),
                eq(List.of(CommandTracker.modelDeviceKey("D-1"), "jiangyi:cmd:model:pending",
                        "jiangyi:cmd:model:9")),
                eq("9"), eq("D-1"), eq("id-1"), anyString(), anyString());
    }

    @Test
    void registerModel_existingDeviceMarker_rejectsWithoutSecondPending() {
        when(redis.execute(org.mockito.ArgumentMatchers.any(
                        org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(), eq("10"), eq("D-1"), eq("id-1"),
                anyString(), anyString())).thenReturn(0L);

        assertFalse(tracker.register(CommandTracker.Kind.MODEL, "10", "D-1", "id-1"));

        verify(zSet, never()).add(anyString(), anyString(), anyDouble());
        verify(hash, never()).putAll(anyString(), any(Map.class));
    }

    @Test
    void completeByDeviceModel_confirmsOnlyMarkerDeploymentAtomically() {
        when(redis.execute(org.mockito.ArgumentMatchers.any(
                        org.springframework.data.redis.core.script.RedisScript.class),
                eq(List.of(CommandTracker.modelDeviceKey("D-1"), "jiangyi:cmd:model:pending")),
                eq("D-1"), eq("jiangyi:cmd:model:"))).thenReturn(1L);

        assertEquals(1, tracker.completeByDevice(CommandTracker.Kind.MODEL, "D-1"));

        verify(redis).execute(org.mockito.ArgumentMatchers.any(
                        org.springframework.data.redis.core.script.RedisScript.class),
                eq(List.of(CommandTracker.modelDeviceKey("D-1"), "jiangyi:cmd:model:pending")),
                eq("D-1"), eq("jiangyi:cmd:model:"));
        verify(zSet, never()).remove(anyString(), eq("8"));
    }

    @Test
    void evictModel_releasesOnlyMatchingDeviceMarker() {
        when(hash.get("jiangyi:cmd:model:9", "deviceId")).thenReturn("D-1");

        tracker.evict(CommandTracker.Kind.MODEL, "9");

        verify(redis).execute(org.mockito.ArgumentMatchers.any(
                        org.springframework.data.redis.core.script.RedisScript.class),
                eq(List.of("jiangyi:cmd:model:9", "jiangyi:cmd:model:pending",
                        CommandTracker.modelDeviceKey("D-1"))), eq("9"));
        verify(redis, never()).delete("jiangyi:cmd:model:9");
    }
}
