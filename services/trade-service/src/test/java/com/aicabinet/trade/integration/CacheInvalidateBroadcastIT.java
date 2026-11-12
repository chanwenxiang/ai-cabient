package com.aicabinet.trade.integration;

import com.aicabinet.trade.support.CacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集群就绪 P-4 的<b>真 Redis 双实例实证</b>（TRI_CLIENT_BACKLOG §六 P-4，2026-11-12）。
 *
 * <p>{@code lookup} 是先 Redis 后 L1：实例 A evict 删掉 Redis 键后，实例 B 的读在
 * Redis miss 时会回落到 B 自己的 L1 旧值——loader 被跳过、旧数据当新数据用；
 * 若 B 是 evict 后第一个读，还会把旧值重新写回 Redis（旧值回灌）。
 * 修复 = evict 向 {@link CacheService#EVICT_CHANNEL} 广播，各实例收到后清各自 L1。</p>
 *
 * <p>本 IT 钉三件事：</p>
 * <ol>
 *   <li>广播在位：对端（接广播）在 evict 后必须拿到新值，不得从 L1 供旧值；</li>
 *   <li>缺陷本体存证：不接广播的裸实例在 Redis miss 窗口内从 L1 供旧值、loader 被跳过；</li>
 *   <li>evictAll 广播同样清空对端 L1。</li>
 * </ol>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class CacheInvalidateBroadcastIT {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
    }

    @Autowired
    /** Spring 容器里的实例：CacheConfig 已把它接上广播监听（= 接收方实例）。 */
    private CacheService receiver;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /** 独立构造的「实例 A」：同一 Redis，但没接广播监听（本测试里它只当发起方）。 */
    @SuppressWarnings("unchecked")
    private CacheService newInstance() {
        org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(redisTemplate);
        return new CacheService(provider, objectMapper);
    }

    private static void awaitTrue(long timeoutMs, String what, Callable<Boolean> cond) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(cond.call())) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待超时（" + timeoutMs + "ms）：" + what);
    }

    @Test
    @DisplayName("evict 广播：对端自装载的 L1 旧值被清、必须走 loader 拿新值")
    void remoteEvict_clearsPeerLocalCache() throws Exception {
        String prefix = "it-evict-" + System.nanoTime();

        // 🔴 对端必须先走 miss 路径自装载（L1+Redis 均有 v1）：lookup 的 Redis 命中
        // 不写 L1——若让发起方先缓存、对端从 Redis 命中，对端 L1 是空的，
        // evict 后直接 miss→loader 拿新值，广播根本没被测到（2026-11-12 首版假绿踩坑）。
        assertEquals("v1", receiver.get(prefix, "k", 60_000, () -> "v1"));

        // 发起方 evict（真实场景：数据变更后失效缓存）；此刻 Redis 已删、对端 L1 旧值独占
        newInstance().evict(prefix);

        // 广播到达前对端从 L1 供旧值（改前永久）；广播清 L1 后必须走 loader 拿新值
        AtomicInteger loads = new AtomicInteger();
        awaitTrue(5000, "对端应在广播清 L1 后走 loader 拿 v2", () ->
                "v2".equals(receiver.get(prefix, "k", 60_000, () -> {
                    loads.incrementAndGet();
                    return "v2";
                })));
        assertTrue(loads.get() > 0, "对端必须真的走了一次 loader（证明 L1 旧值被清而不是继续命中）");
    }

    @Test
    @DisplayName("缺陷本体存证：无广播的裸实例从 L1 供旧值、loader 被跳过")
    void withoutBroadcast_peerServesStaleFromL1() {
        CacheService stale = newInstance(); // 不接广播 = 缺陷本体
        String prefix = "it-stale-" + System.nanoTime();

        assertEquals("v1", stale.get(prefix, "k", 60_000, () -> "v1"));
        // 另一实例 evict（真实场景：数据变更后失效缓存）
        newInstance().evict(prefix);
        // stale 在 Redis miss 窗口内读：先 Redis（miss）后 L1（旧值命中）
        assertEquals("v1", stale.get(prefix, "k", 60_000, () -> "fresh-from-db"),
                "无广播时对端从 L1 供旧值、loader 被跳过 = 缺陷本体（广播修复的就是它）");
    }

    @Test
    @DisplayName("evictAll 广播：清空对端全部 L1")
    void evictAll_broadcastClearsPeerLocalCache() throws Exception {
        CacheService evictor = newInstance();
        String prefix = "it-all-" + System.nanoTime();

        assertEquals("v1", receiver.get(prefix, "k", 60_000, () -> "v1"));
        assertEquals("v2", receiver.get("other-" + prefix, "k", 60_000, () -> "v2"));

        evictor.evictAll();
        awaitTrue(5000, "对端 L1 应被 evictAll 广播清空", () -> {
            boolean first = "fresh1".equals(receiver.get(prefix, "k", 60_000, () -> "fresh1"));
            boolean second = "fresh2".equals(receiver.get("other-" + prefix, "k", 60_000, () -> "fresh2"));
            return first && second;
        });
    }
}
