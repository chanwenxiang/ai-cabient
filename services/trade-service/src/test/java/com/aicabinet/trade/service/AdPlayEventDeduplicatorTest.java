package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 投放事件去重的判据。
 *
 * <p>覆盖面：本地模式（首次/重复/异键/归还/参数不全）、Redis 模式（跨实例共享 + TTL 参数）、
 * Redis 故障回退。
 *
 * <p>⚠️ **未覆盖**：本地模式下「窗口**过期**后应放行」——需要可控时钟。为此给生产类引入
 * {@code Clock} 抽象不划算，故窗口长度改由 Redis 分支的 TTL 参数断言守着
 * （{@link #redis_sharesAcrossInstances} 里 `verify(... Duration.ofMillis(60_000))`）。
 * 这是本文件的已知覆盖边界，不是遗漏。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdPlayEventDeduplicatorTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    /** 无 Redis（本地模式）：首次通过，窗口内重复被挡。 */
    @Test
    void local_firstAcquireTrue_thenDuplicateFalse() {
        AdPlayEventDeduplicator d = new AdPlayEventDeduplicator();

        assertTrue(d.tryAcquire("CAB-1", 3L, 100L, "IMPRESSION"));
        assertFalse(d.tryAcquire("CAB-1", 3L, 100L, "IMPRESSION"), "同组合第二次必须被挡");
        assertFalse(d.tryAcquire("CAB-1", 3L, 100L, "IMPRESSION"));
    }

    /** 四个维度任一不同 ⇒ 不是同一组合，各自都要能记上。 */
    @Test
    void local_distinctCombinationsAreIndependent() {
        AdPlayEventDeduplicator d = new AdPlayEventDeduplicator();

        assertTrue(d.tryAcquire("CAB-1", 3L, 100L, "IMPRESSION"));
        assertTrue(d.tryAcquire("CAB-1", 3L, 100L, "COMPLETE"), "换事件类型");
        assertTrue(d.tryAcquire("CAB-1", 3L, 101L, "IMPRESSION"), "换素材");
        assertTrue(d.tryAcquire("CAB-1", 4L, 100L, "IMPRESSION"), "换计划");
        assertTrue(d.tryAcquire("CAB-2", 3L, 100L, "IMPRESSION"), "换设备");
        // 素材缺失（-）与具体素材也要区分开
        assertTrue(d.tryAcquire("CAB-1", 3L, null, "IMPRESSION"));
    }

    /** 落库失败后归还资格 ⇒ 同窗口内重试仍可通过（否则一次 DB 抖动就永久吞掉这次曝光）。 */
    @Test
    void local_releaseAllowsRetryWithinWindow() {
        AdPlayEventDeduplicator d = new AdPlayEventDeduplicator();

        assertTrue(d.tryAcquire("CAB-R", 3L, 100L, "CLICK"));
        assertFalse(d.tryAcquire("CAB-R", 3L, 100L, "CLICK"));

        d.release("CAB-R", 3L, 100L, "CLICK");

        assertTrue(d.tryAcquire("CAB-R", 3L, 100L, "CLICK"), "归还后必须能重新申领");
    }

    /** 参数不全（调用方已有前置校验）⇒ 放行，宁可不去重也不吞事件。 */
    @Test
    void local_incompleteArgsPassThrough() {
        AdPlayEventDeduplicator d = new AdPlayEventDeduplicator();

        assertTrue(d.tryAcquire(null, 3L, 100L, "IMPRESSION"));
        assertTrue(d.tryAcquire("  ", 3L, 100L, "IMPRESSION"));
        assertTrue(d.tryAcquire("CAB-1", null, 100L, "IMPRESSION"));
        assertTrue(d.tryAcquire("CAB-1", 3L, 100L, null));
        // 不做去重 ⇒ 重复调用也应一直放行
        assertTrue(d.tryAcquire(null, 3L, 100L, "IMPRESSION"));
    }

    /** Redis 模式：键跨实例共享（多副本部署时同一个组合只能记一次），且 TTL = 60s。 */
    @Test
    void redis_sharesAcrossInstances() {
        Map<String, String> store = new ConcurrentHashMap<>();
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenAnswer(inv -> store.putIfAbsent(inv.getArgument(0), "1") == null);

        AdPlayEventDeduplicator a = new AdPlayEventDeduplicator(provider(redis));
        AdPlayEventDeduplicator b = new AdPlayEventDeduplicator(provider(redis));

        assertTrue(a.tryAcquire("CAB-Q", 3L, 100L, "IMPRESSION"));
        assertFalse(b.tryAcquire("CAB-Q", 3L, 100L, "IMPRESSION"), "另一实例必须被挡（共享键）");

        verify(valueOps, times(2)).setIfAbsent(
                eq("aicabinet:ad-play-dedup:CAB-Q:3:100:IMPRESSION"),
                eq("1"),
                eq(Duration.ofMillis(60_000)));
    }

    /** Redis 异常 ⇒ 回退本地，行为与本地模式一致（宁可少挡，不可误挡）。 */
    @Test
    void redisFailure_fallsBackToLocal() {
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        AdPlayEventDeduplicator d = new AdPlayEventDeduplicator(provider(redis));

        assertTrue(d.tryAcquire("CAB-F", 3L, 100L, "IMPRESSION"));
        assertFalse(d.tryAcquire("CAB-F", 3L, 100L, "IMPRESSION"), "回退后仍要能挡重复");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> provider(StringRedisTemplate redis) {
        ObjectProvider<StringRedisTemplate> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(redis);
        return p;
    }
}
