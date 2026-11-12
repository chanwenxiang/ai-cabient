package com.aicabinet.trade.integration;

import com.aicabinet.trade.mapper.ScheduledTaskMapper;
import com.aicabinet.trade.service.DistributedLockService;
import com.aicabinet.trade.service.ScheduledTaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 集群就绪 P-3 的<b>真 Redis 并发实证</b>（TRI_CLIENT_BACKLOG §六 P-3，2026-11-12）。
 *
 * <p>{@code tryBegin} 用 Redisson 显式租约（{@code tryLock(wait, lease)}）——
 * 显式租约下 Redisson watchdog 不激活，任务体耗时超过租约时锁自然过期，
 * 第二实例能进临界区造成双跑；而 {@code finish} 里的 unlock 会因「非当前线程持有」被跳过。
 * 修复 = 租约心跳：持锁后按租约 1/3 周期用 owner 校验的 Lua 续租。</p>
 *
 * <p>本 IT 钉三件事：</p>
 * <ol>
 *   <li>心跳在位：任务体（3s）超过租约（2s）后锁仍被持有，第二实例 tryBegin 必须被拒；</li>
 *   <li>owner 校验：假冒他人线程 id 续租必须失败且不延长 TTL（防挂死任务把别人的锁续长）；</li>
 *   <li>缺陷本体存证：无续租时租约一过期第二实例就能进——心跳解决的就是这个缺口。</li>
 * </ol>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class ScheduledTaskLeaseRenewalIT {

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
    private DistributedLockService lockService;

    /** 独立构造的「实例」：mock 掉台账读写（本 IT 只钉锁语义，不落执行记录）。 */
    private ScheduledTaskService newInstance() {
        ScheduledTaskMapper taskMapper = mock(ScheduledTaskMapper.class);
        when(taskMapper.selectById(anyString())).thenReturn(null);
        when(taskMapper.findByIdForUpdate(anyString())).thenReturn(Optional.empty());
        return new ScheduledTaskService(taskMapper, null, lockService, null, false);
    }

    @Test
    @DisplayName("任务体超过租约：心跳续租保锁、第二实例被拒；finish 释放后可进")
    void longTaskWithLeaseHeartbeat_keepsSecondInstanceOut() throws Exception {
        ScheduledTaskService first = newInstance();
        ScheduledTaskService second = newInstance();
        String key = "it-lease-" + System.nanoTime();

        // 租约 2s，心跳周期 667ms；任务体 3s = 1.5 倍租约
        assertTrue(first.tryBegin(key, 2), "第一实例应拿到锁");
        Thread.sleep(3000);

        // 无续租时锁在 t=2s 就该没了；仍被持有 ⇒ 心跳确实在续
        assertTrue(lockService.isLocked("job:" + key),
                "任务体超过租约后锁必须仍被持有（心跳续租）");
        // 🔴 第二实例必须在独立线程跑 tryBegin：Redisson 锁按「clientId:threadId」判重入，
        // 同线程的第二次 tryBegin 是可重入拿锁，测不出互斥（2026-11-12 首版踩坑）。
        java.util.concurrent.ExecutorService peer = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            boolean admitted = peer.submit(() -> second.tryBegin(key, 2)).get(10, TimeUnit.SECONDS);
            assertFalse(admitted,
                    "任务体超过租约时第二实例不得进临界区（双跑）");

            // 主线程（持锁线程）finish 释放后，第二实例应能进
            first.finish(key, "SUCCESS", "lease-it", 0);
            boolean admittedAfterRelease = peer.submit(() -> second.tryBegin(key, 2)).get(10, TimeUnit.SECONDS);
            assertTrue(admittedAfterRelease, "finish 释放后第二实例应能进");
            peer.submit(() -> second.finish(key, "SUCCESS", "lease-it", 0)).get(10, TimeUnit.SECONDS);
        } finally {
            peer.shutdownNow();
        }
    }

    @Test
    @DisplayName("owner 校验：假冒线程 id 续租失败且不延长 TTL")
    void renew_rejectsForeignOwner() throws Exception {
        String key = "it-owner-" + System.nanoTime();
        assertTrue(lockService.tryLock("job:" + key, 2, 0), "当前线程应拿到锁");

        // R18-7 三态：假冒 owner 属于「锁在但已易主」⇒ 必须返回 LOST（而非 TRANSIENT 瞬断）
        assertEquals(com.aicabinet.trade.service.DistributedLockService.RenewOutcome.LOST,
                lockService.renew("job:" + key, 10, 987654321L),
                "假冒 owner 续租必须返回 LOST");
        Thread.sleep(2100);
        assertFalse(lockService.isLocked("job:" + key),
                "错误 owner 的续租不得延长 TTL（否则挂死任务会钉死锁、堵死自愈）");
    }

    @Test
    @DisplayName("缺陷本体存证：无续租时租约一过期第二实例就能进")
    void leaseExpiryWithoutRenewal_admitsSecondInstance() throws Exception {
        String key = "it-raw-" + System.nanoTime();
        assertTrue(lockService.tryLock("job:" + key, 2, 0));
        Thread.sleep(2500); // 无续租，超租约
        assertFalse(lockService.isLocked("job:" + key), "无续租时锁应已按租约过期");
        assertTrue(lockService.tryLock("job:" + key, 2, 0),
                "无续租 = 双跑缺口本体：第二实例能进（心跳解决的就是这个）");
        lockService.unlock("job:" + key);
    }
}
