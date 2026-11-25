package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.PlatformFundFlow;
import com.aicabinet.trade.service.PlatformFundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.awaitility.Awaitility.await;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class PlatformFundPayoutIdempotencySqlIT {

    private static final String CHANNEL = "微信";
    private static final String REF_TYPE = "MERCHANT_WITHDRAW";
    private static final String REF_PREFIX = "IT-FUND-";

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "24");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformFundService fundService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetAccount() {
        jdbc.update("DELETE FROM platform_fund_flow WHERE ref_id LIKE ? OR ref_id = '7301'", REF_PREFIX + "%");
        jdbc.update("""
                INSERT INTO platform_fund_account
                    (channel, account_name, balance_cents, status, created_at, updated_at)
                VALUES (?, '隔离测试资金账户', 10000, 'ACTIVE', now(), now())
                ON CONFLICT (channel) DO UPDATE
                SET balance_cents = 10000, status = 'ACTIVE', updated_at = now()
                """, CHANNEL);
    }

    @Test
    @DisplayName("V509 唯一索引只约束提现业务引用，人工无引用流水仍可重复登记")
    void uniqueIndexRejectsDuplicatePayoutButAllowsManualFlows() {
        Integer indexCount = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND tablename = 'platform_fund_flow'
                  AND indexname IN ('uq_platform_fund_payout_ref', 'uq_platform_fund_bill_ref')
                """, Integer.class);
        assertEquals(2, indexCount);

        insertPayout(REF_PREFIX + "DIRECT");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertPayout(REF_PREFIX + "DIRECT"));
        insertFundBill(REF_PREFIX + "BILL");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertFundBill(REF_PREFIX + "BILL"));

        fundService.recharge(accountId(), 100L, 1L, "隔离测试充值");
        fundService.recharge(accountId(), 100L, 1L, "隔离测试充值");
        assertEquals(2, countFlows(PlatformFundFlow.TYPE_RECHARGE));
        assertEquals(10200, balanceCents());
    }

    @Test
    @DisplayName("八副本带外层事务登记同一提现只扣一次，内层冲突不污染外层提交")
    void concurrentDuplicatePayoutWritesBalanceAndFlowAtomically() throws Exception {
        int replicas = 8;
        ExecutorService executor = Executors.newFixedThreadPool(replicas);
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);
        CountDownLatch ready = new CountDownLatch(replicas);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (Connection accountLock = jdbc.getDataSource().getConnection()) {
            accountLock.setAutoCommit(false);
            try (PreparedStatement statement = accountLock.prepareStatement(
                    "SELECT account_id FROM platform_fund_account WHERE channel = ? FOR UPDATE")) {
                statement.setString(1, CHANNEL);
                statement.execute();
            }
            for (int i = 0; i < replicas; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    outerTransaction.executeWithoutResult(status -> {
                        fundService.recordPayoutQuietly(CHANNEL, 500L, 7301L);
                        jdbc.update("UPDATE platform_fund_account SET remark = ? WHERE channel = ?",
                                "outer committed", CHANNEL);
                    });
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            await().atMost(10, TimeUnit.SECONDS).until(() -> blockedAccountLocks() == replicas);
            accountLock.rollback();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, countFlows(PlatformFundFlow.TYPE_PAYOUT));
        assertEquals(9500, balanceCents());
        assertEquals(1, countFlowsByRef("7301"));
        assertEquals("outer committed", jdbc.queryForObject(
                "SELECT remark FROM platform_fund_account WHERE channel = ?", String.class, CHANNEL));
    }

    private int blockedAccountLocks() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database()
                  AND wait_event_type = 'Lock'
                  AND (query LIKE 'SELECT%FROM platform_fund_account%FOR UPDATE%'
                       OR query LIKE 'UPDATE platform_fund_account SET balance_cents%')
                """, Integer.class);
        return count == null ? 0 : count;
    }

    private void insertPayout(String refId) {
        jdbc.update("""
                INSERT INTO platform_fund_flow
                    (account_id, flow_type, amount_cents, balance_after_cents,
                     ref_type, ref_id, created_at)
                VALUES (?, 'PAYOUT', -500, 9500, ?, ?, now())
                """, accountId(), REF_TYPE, refId);
    }

    private void insertFundBill(String refId) {
        jdbc.update("""
                INSERT INTO platform_fund_flow
                    (account_id, flow_type, amount_cents, balance_after_cents,
                     ref_type, ref_id, created_at)
                VALUES (?, 'ADJUST', 1, 1, 'WECHAT_FUND_BILL', ?, now())
                """, accountId(), refId);
    }

    private long accountId() {
        return jdbc.queryForObject("SELECT account_id FROM platform_fund_account WHERE channel = ?",
                Long.class, CHANNEL);
    }

    private long balanceCents() {
        return jdbc.queryForObject("SELECT balance_cents FROM platform_fund_account WHERE channel = ?",
                Long.class, CHANNEL);
    }

    private int countFlows(String flowType) {
        return jdbc.queryForObject("SELECT count(*) FROM platform_fund_flow WHERE account_id = ? AND flow_type = ?",
                Integer.class, accountId(), flowType);
    }

    private int countFlowsByRef(String refId) {
        return jdbc.queryForObject("SELECT count(*) FROM platform_fund_flow WHERE ref_type = ? AND ref_id = ?",
                Integer.class, REF_TYPE, refId);
    }

}
