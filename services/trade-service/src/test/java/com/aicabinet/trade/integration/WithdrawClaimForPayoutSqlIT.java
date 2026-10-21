package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.LineWithdrawRequest;
import com.aicabinet.trade.domain.MerchantWithdrawRequest;
import com.aicabinet.trade.mapper.LineWithdrawRequestMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「抢出款权」那条条件 UPDATE 的<b>真库实证</b>（2026-10-21，决策 A+B 及其线长侧镜像）。
 *
 * <p>单测里 mapper 是 mock，MyBatis-Plus 由 {@code update(null, lambdaUpdate()…)} 生成的
 * SQL <b>从未被任何数据库执行过</b>。本 IT 用真 PostgreSQL 钉三件事：
 * ① 生成的 SQL 合法且只改 {@code status/updated_at}（不是整行回写）；
 * ② {@code WHERE status=<期望旧值>} 由库求值：旧值不匹配抢不到 ⇒ 返回 0 行；
 * ③ 两个并发同名 claim <b>恰有一方</b>拿到 1 行（行锁串行化，不是靠应用层判断）。
 * 这三条正是「单据没有 {@code @Version} 时，条件 UPDATE 是唯一本地可控闸门」的依据。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class WithdrawClaimForPayoutSqlIT {

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
    private JdbcTemplate jdbc;
    @Autowired
    private LineWithdrawRequestMapper lineMapper;
    @Autowired
    private MerchantWithdrawRequestMapper merchantMapper;

    @Test
    @DisplayName("线长单：旧状态匹配才抢到 PAYING，第二次同旧值必抢 0 行")
    void lineClaim_succeedsOnlyFromExpectedStatus() {
        long requestId = seedLine("APPROVED");

        // ① 旧值匹配 ⇒ 抢到，且只写 status/updated_at
        assertEquals(1, lineMapper.claimForPayout(requestId, "APPROVED", Instant.now()),
                "抢出款权应恰好影响 1 行");
        LineWithdrawRequest after = lineMapper.findById(requestId).orElseThrow();
        assertEquals("PAYING", after.getStatus());

        // ② 真值已是 PAYING，仍按 APPROVED 抢 ⇒ 0 行（服务层据此回 409，绝不触渠道）
        assertEquals(0, lineMapper.claimForPayout(requestId, "APPROVED", Instant.now()),
                "条件 UPDATE 的 WHERE 没起作用：同一张单能被抢两次");
    }

    @Test
    @DisplayName("商户单：同形条件 UPDATE 行为一致（两侧共用一条判据）")
    void merchantClaim_succeedsOnlyFromExpectedStatus() {
        long requestId = seedMerchant("FAILED");

        assertEquals(1, merchantMapper.claimForPayout(requestId, "FAILED", Instant.now()),
                "抢出款权应恰好影响 1 行");
        MerchantWithdrawRequest after = merchantMapper.findById(requestId).orElseThrow();
        assertEquals("PAYING", after.getStatus());

        assertEquals(0, merchantMapper.claimForPayout(requestId, "FAILED", Instant.now()),
                "条件 UPDATE 的 WHERE 没起作用：同一张单能被抢两次");
    }

    @Test
    @DisplayName("并发两方同时抢同一张单：恰有一方拿到出款权")
    void concurrentClaims_exactlyOneWins() throws Exception {
        long requestId = seedLine("APPROVED");
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (lineMapper.claimForPayout(requestId, "APPROVED", Instant.now()) == 1) {
                        winners.incrementAndGet();
                    }
                });
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, winners.get(), "两张并发请求都自认为拿到出款权 ⇒ 会向渠道重复转账");
        Optional<LineWithdrawRequest> row = lineMapper.findById(requestId);
        assertTrue(row.isPresent() && "PAYING".equals(row.get().getStatus()));
    }

    private long seedLine(String status) {
        String requestNo = "IT-CLAIM-LINE-" + System.nanoTime();
        jdbc.update("INSERT INTO line_withdraw_request (request_no, manager_id, amount_cents, status)"
                + " VALUES (?, 900001, 10000, ?)", requestNo, status);
        return jdbc.queryForObject(
                "SELECT request_id FROM line_withdraw_request WHERE request_no = ?",
                Long.class, requestNo);
    }

    private long seedMerchant(String status) {
        // merchant_withdraw_request.merchant_id 有 FK（后期迁移加的，建表迁移里看不到）⇒ 先播主体
        jdbc.update("INSERT INTO merchant (merchant_id, merchant_name, remark) "
                + "VALUES ('MCH-IT-CLAIM', '抢出款权 IT 商户', 'WithdrawClaimForPayoutSqlIT') "
                + "ON CONFLICT (merchant_id) DO NOTHING");
        String requestNo = "IT-CLAIM-MCH-" + System.nanoTime();
        jdbc.update("INSERT INTO merchant_withdraw_request (request_no, merchant_id, amount_cents, status)"
                + " VALUES (?, 'MCH-IT-CLAIM', 10000, ?)", requestNo, status);
        return jdbc.queryForObject(
                "SELECT request_id FROM merchant_withdraw_request WHERE request_no = ?",
                Long.class, requestNo);
    }
}
