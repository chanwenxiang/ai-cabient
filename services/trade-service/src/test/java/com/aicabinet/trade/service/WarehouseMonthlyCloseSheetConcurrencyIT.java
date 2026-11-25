package com.aicabinet.trade.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MA-452：仓+月 advisory xact lock 与月结行锁的真库并发回归。 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class WarehouseMonthlyCloseSheetConcurrencyIT {

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

    private static final String WH = "WH-MONTHLY-CONCURRENCY";
    private static final String MONTH = "2026-10";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private WarehouseMonthlyCloseSheetService service;

    @BeforeEach
    void seedWarehouse() {
        jdbc.update("DELETE FROM warehouse_monthly_close_line WHERE close_id IN "
                + "(SELECT close_id FROM warehouse_monthly_close WHERE warehouse_id = ?)", WH);
        jdbc.update("DELETE FROM warehouse_monthly_close WHERE warehouse_id = ?", WH);
        jdbc.update("DELETE FROM warehouse WHERE warehouse_id = ?", WH);
        jdbc.update("INSERT INTO warehouse (warehouse_id, warehouse_name) VALUES (?, '月结并发仓')", WH);
    }

    @Test
    void concurrentEnsureDraft_createsExactlyOneSheet() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> {
                start.await();
                return service.ensureDraft(0L, WH, MONTH);
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await();
                return service.ensureDraft(0L, WH, MONTH);
            });
            start.countDown();

            boolean created = first.get(30, TimeUnit.SECONDS);
            boolean skipped = second.get(30, TimeUnit.SECONDS);
            assertTrue(created ^ skipped, "同一仓月只能一个事务新建，另一个必须看到已存在草稿");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM warehouse_monthly_close WHERE warehouse_id = ? AND year_month = ?",
                    Integer.class, WH, MONTH));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentApproveAndEdit_approvalCanNeverBeRevertedToDraft() throws Exception {
        Long closeId = service.generateSheet(0L, WH, MONTH).closeId();
        jdbc.update("INSERT INTO warehouse_monthly_close_line "
                        + "(close_id, sku_id, expected_qty, counted_qty, gap_qty, gap_disposition) "
                        + "VALUES (?, 'SKU-CONCURRENCY', 10, 10, 0, 'PENDING')",
                closeId);
        Long lineId = jdbc.queryForObject(
                "SELECT line_id FROM warehouse_monthly_close_line WHERE close_id = ?", Long.class, closeId);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> approve = executor.submit(() -> {
                start.await();
                service.approve(0L, closeId);
                return null;
            });
            Future<Boolean> edit = executor.submit(() -> {
                start.await();
                try {
                    service.updateLine(0L, closeId, lineId, 9, null, null, null);
                    return true;
                } catch (ResponseStatusException e) {
                    assertEquals(409, e.getStatusCode().value());
                    return false;
                }
            });
            start.countDown();
            approve.get(30, TimeUnit.SECONDS);
            boolean edited = edit.get(30, TimeUnit.SECONDS);

            assertEquals("APPROVED", jdbc.queryForObject(
                    "SELECT status FROM warehouse_monthly_close WHERE close_id = ?", String.class, closeId));
            assertEquals(edited, jdbc.queryForObject(
                    "SELECT edited_flag FROM warehouse_monthly_close_line WHERE line_id = ?",
                    Boolean.class, lineId).booleanValue());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentRegenerateAndApprove_neverDeletesApprovedSheet() throws Exception {
        Long closeId = service.generateSheet(0L, WH, MONTH).closeId();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> approved = executor.submit(() -> {
                start.await();
                try {
                    service.approve(0L, closeId);
                    return true;
                } catch (ResponseStatusException e) {
                    assertTrue(e.getStatusCode().value() == 404 || e.getStatusCode().value() == 409);
                    return false;
                }
            });
            Future<Boolean> regenerated = executor.submit(() -> {
                start.await();
                try {
                    service.generateSheet(0L, WH, MONTH);
                    return true;
                } catch (ResponseStatusException e) {
                    assertEquals(409, e.getStatusCode().value());
                    return false;
                }
            });
            start.countDown();
            boolean didApprove = approved.get(30, TimeUnit.SECONDS);
            boolean didRegenerate = regenerated.get(30, TimeUnit.SECONDS);

            assertFalse(didApprove && didRegenerate, "重生成与审批必须串行，不能同时成功");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM warehouse_monthly_close WHERE warehouse_id = ? AND year_month = ?",
                    Integer.class, WH, MONTH));
            assertEquals(didApprove ? "APPROVED" : "DRAFT", jdbc.queryForObject(
                    "SELECT status FROM warehouse_monthly_close WHERE warehouse_id = ? AND year_month = ?",
                    String.class, WH, MONTH));
        } finally {
            executor.shutdownNow();
        }
    }
}
