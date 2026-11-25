package com.aicabinet.trade.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class StartupStateSqlIT {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(
            org.testcontainers.utility.DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
        registry.add("aicabinet.device-service.url", () -> "http://127.0.0.1:1");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SysDictBootstrap bootstrap;
    @Autowired private UserConsentLogService consentLogService;

    @BeforeEach
    void clearNotificationRows() {
        jdbc.update("DELETE FROM notification_log WHERE template_code = 'STARTUP-STATE-IT'");
        jdbc.update("DELETE FROM platform_fund_flow WHERE account_id IN "
                + "(SELECT account_id FROM platform_fund_account WHERE channel = 'STARTUP-STATE-IT')");
        jdbc.update("DELETE FROM platform_fund_account WHERE channel = 'STARTUP-STATE-IT'");
    }

    @Test
    void twoInstancesSeedEmptyDictionary_concurrentlyWithoutDuplicateKey() throws Exception {
        jdbc.update("DELETE FROM sys_dict_data");
        jdbc.update("DELETE FROM sys_dict_type");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> {
                start.await();
                bootstrap.run(null);
                return true;
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await();
                bootstrap.run(null);
                return true;
            });
            start.countDown();
            assertTrue(first.get(30, TimeUnit.SECONDS));
            assertTrue(second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        Integer deviceTypes = jdbc.queryForObject(
                "SELECT count(*) FROM sys_dict_type WHERE dict_type = 'device_type'", Integer.class);
        Integer allTypes = jdbc.queryForObject("SELECT count(*) FROM sys_dict_type", Integer.class);
        assertEquals(1, deviceTypes, "双实例并发种子后 device_type 必须恰好一行");
        assertTrue(allTypes > 1, "种子必须完整，不能因竞争只写入部分字典");
    }

    @Test
    void consentLog_sqlFailureIsCaughtOutsideTransactionProxy() {
        jdbc.execute("ALTER TABLE user_consent_log RENAME TO user_consent_log_unavailable");
        try {
            assertDoesNotThrow(() -> consentLogService.recordPrivacyConsent(730001L));
        } finally {
            jdbc.execute("ALTER TABLE user_consent_log_unavailable RENAME TO user_consent_log");
        }
    }

    @Test
    void platformFundFlow_payoutRefIsDatabaseIdempotent() {
        Long accountId = jdbc.queryForObject("INSERT INTO platform_fund_account "
                + "(channel, account_name, balance_cents) VALUES ('STARTUP-STATE-IT', 'IT', 1000) "
                + "RETURNING account_id", Long.class);
        jdbc.update("INSERT INTO platform_fund_flow "
                + "(account_id, flow_type, amount_cents, balance_after_cents, ref_type, ref_id) "
                + "VALUES (?, 'PAYOUT', -100, 900, 'MERCHANT_WITHDRAW', '42')", accountId);

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO platform_fund_flow "
                        + "(account_id, flow_type, amount_cents, balance_after_cents, ref_type, ref_id) "
                        + "VALUES (?, 'PAYOUT', -100, 800, 'MERCHANT_WITHDRAW', '42')", accountId));
    }

    @Test
    void notificationLog_allowsFailedAndRejectsUnknownStatus() {
        jdbc.update("INSERT INTO notification_log "
                + "(template_code, channel, audience, title, body, status) "
                + "VALUES ('STARTUP-STATE-IT', 'SMS', 'CONSUMER', 'title', 'body', 'FAILED')");
        Integer failed = jdbc.queryForObject("SELECT count(*) FROM notification_log "
                + "WHERE template_code = 'STARTUP-STATE-IT' AND status = 'FAILED'", Integer.class);
        assertEquals(1, failed);

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO notification_log "
                        + "(template_code, channel, audience, title, body, status) "
                        + "VALUES ('STARTUP-STATE-IT', 'SMS', 'CONSUMER', 'title', 'body', 'UNKNOWN')"));
    }
}
