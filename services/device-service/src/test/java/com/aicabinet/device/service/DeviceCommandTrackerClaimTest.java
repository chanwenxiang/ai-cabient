package com.aicabinet.device.service;

import com.aicabinet.device.client.TradeServiceClient;
import com.aicabinet.device.metrics.DeviceMqttMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R19-18 多实例抢占：device-service 可横向扩，两个实例会各自 {@code rangeByScore} 到
 * **同一批**到期命令。判据是 {@code ZREM} 的返回值——只有把成员真正删掉的那个实例
 * （返回 1）才处置，另一个（返回 0）必须什么都不做。
 *
 * <p>这条判据的价值在于「重复处置」不会有人报障（trade 侧 CAS 挡住重复入账），
 * 只会表现为重复 HTTP 与重复告警，所以必须靠用例钉住。
 */
class DeviceCommandTrackerClaimTest {

    private static final String PENDING_ZSET = "aicabinet:device-cmd:pending-z";
    private static final String CMD = "cmd-claim-1";
    private static final String HASH_KEY = "aicabinet:device-cmd:" + CMD;

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate redisReturning(Long zremResult) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForHash()).thenReturn(hash);
        // P3-r19-30：扫描改为分批（5 参 rangeByScore 带 offset/count）。桩必须是 5 参版本，
        // 否则 Mockito 默认返回 null，用例会「因为没扫到东西」而假绿。
        when(zset.rangeByScore(eq(PENDING_ZSET), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(Set.of(CMD));
        when(zset.remove(PENDING_ZSET, CMD)).thenReturn(zremResult);
        when(hash.entries(HASH_KEY)).thenReturn(Map.of(
                "commandId", CMD,
                "deviceId", "CAB-001",
                "sessionId", "sess-claim",
                "status", "PENDING",
                "publishedAtMs", "1000"));
        return redis;
    }

    @Test
    @SuppressWarnings("unchecked")
    void otherInstanceClaimed_itDoesNothing() {
        StringRedisTemplate redis = redisReturning(0L);
        DeviceCommandTracker tracker = new DeviceCommandTracker(
                new DeviceMqttMetrics(new SimpleMeterRegistry()), redis);

        tracker.expireCommandsRedis();

        // 连状态都不该读：抢占失败直接跳过，避免对同一命令做第二次处理
        verify(redis, never()).opsForHash();
        verify(redis, never()).expire(anyString(), any(java.time.Duration.class));
        tracker.stop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void claimSucceeded_writesTimeoutAndNotifies() {
        StringRedisTemplate redis = redisReturning(1L);
        TradeServiceClient tradeClient = mock(TradeServiceClient.class);
        DeviceCommandTracker tracker = new DeviceCommandTracker(
                new DeviceMqttMetrics(new SimpleMeterRegistry()), redis, tradeClient);

        tracker.expireCommandsRedis();

        HashOperations<String, Object, Object> hash = redis.opsForHash();
        verify(hash, times(1)).putAll(eq(HASH_KEY), any(Map.class));
        verify(tradeClient, org.mockito.Mockito.timeout(2000))
                .openDoorFailed(eq("sess-claim"), anyString());
        tracker.stop();
    }

    /**
     * P3-r19-30：扫描必须**分批**。原来一次性 rangeByScore 拉全量 —— device-service 停机
     * 一天后重启，zset 里全是过期待确认，这个调用会把成千上万条 String 拉进内存，再对每条
     * 各做一次 ZREM + HGETALL，全压在同一条 5s 调度线程上（积压 1 万条 ⇒ 单轮几十秒）。
     *
     * <p>判据：第一批取满（== 批大小）时必须继续取第二批；本用例让第一批返回 200 条、
     * 第二批返回空，断言 rangeByScore 恰好被调用 2 次、ZREM 恰好 200 次。
     * 200 与 {@code EXPIRE_SCAN_BATCH} 同值 —— 改批大小会让本用例变红，这是有意的：
     * 改判据必须连测试一起改，不能悄悄放宽。
     */
    @Test
    @SuppressWarnings("unchecked")
    void fullBatch_triggersAnotherScanRound() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);

        Set<String> full = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 200; i += 1) {
            full.add("cmd-" + i);
        }
        // 第一批满批，第二批空 ⇒ 扫两轮后收手
        when(zset.rangeByScore(eq(PENDING_ZSET), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(full, Set.of());
        // 全部被别人抢走（返回 0）⇒ 不读状态、不写 TIMEOUT，纯测扫描节奏
        when(zset.remove(eq(PENDING_ZSET), anyString())).thenReturn(0L);

        DeviceCommandTracker tracker = new DeviceCommandTracker(
                new DeviceMqttMetrics(new SimpleMeterRegistry()), redis);

        tracker.expireCommandsRedis();

        verify(zset, times(2))
                .rangeByScore(eq(PENDING_ZSET), anyDouble(), anyDouble(), anyLong(), anyLong());
        verify(zset, times(200)).remove(eq(PENDING_ZSET), anyString());
        verify(redis, never()).opsForHash();
        tracker.stop();
    }
}
