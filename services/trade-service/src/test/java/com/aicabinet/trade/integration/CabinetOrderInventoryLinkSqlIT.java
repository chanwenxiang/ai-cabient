package com.aicabinet.trade.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AU-191（2026-10-19 批次 12）真库回归：V271 的 DEFERRABLE 约束触发器
 * {@code trg_cabinet_order_inventory_link} 是「钱货关联」的最后一道 DB 防线——
 * PAID/PARTIAL_REFUNDED 且带商品行的订单，提交时必须 {@code inventory_deducted=true}
 * （设备有 lot 台账时还须有 SALE inventory_movement）。此前全测试树零触达
 * （应用层测试不连库、无任何 IT），本 IT 用原生 SQL 在真库上钉住提交期语义：
 * <ul>
 *   <li>合规路径：PAID + line + inventory_deducted=true ⇒ 提交成功；</li>
 *   <li>违规路径：PAID + line + inventory_deducted=false ⇒ <b>commit 时</b>炸
 *       （DEFERRED 特性——语句期不炸、提交期炸），且事务回滚零残留。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class CabinetOrderInventoryLinkSqlIT {

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
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    private void seedSku() {
        // cabinet_order_line.sku_id 有外键 ⇒ 先造最小 SKU 行（ON CONFLICT 幂等）
        jdbc.update("""
                INSERT INTO sku_catalog (sku_id, sku_name, price_cents) VALUES ('SKU-IT', 'IT 商品', 500)
                ON CONFLICT (sku_id) DO NOTHING
                """);
    }

    private void seedDeviceAndUser(String deviceId) {
        jdbc.update("""
                INSERT INTO device_info (device_id, device_type, online_status)
                VALUES (?, 'AI_CABINET_V1', 'OFFLINE') ON CONFLICT (device_id) DO NOTHING
                """, deviceId);
        jdbc.update("""
                INSERT INTO user_info (user_id, phone_number) VALUES (?, ?)
                ON CONFLICT (user_id) DO NOTHING
                """, 771001L, "77100000001");
    }

    private void insertSession(String sessionId, String deviceId) {
        jdbc.update("""
                INSERT INTO shopping_session (session_id, user_id, device_id, state, open_time, close_time)
                VALUES (?, 771001, ?, 'SETTLED', now(), now())
                """, sessionId, deviceId);
    }

    private void insertOrder(String orderId, String sessionId, String deviceId, boolean deducted) {
        jdbc.update("""
                INSERT INTO cabinet_order (order_id, session_id, user_id, device_id,
                                           total_amount_cents, status, pay_channel, inventory_deducted)
                VALUES (?, ?, 771001, ?, 500, 'PAID', 'BALANCE', ?)
                """, orderId, sessionId, deviceId, deducted);
    }

    private void insertLine(String orderId) {
        jdbc.update("""
                INSERT INTO cabinet_order_line (order_id, sku_id, sku_name, quantity, unit_price_cents, line_amount_cents)
                VALUES (?, 'SKU-IT', 'IT 商品', 1, 500, 500)
                """, orderId);
    }

    @Test
    void paidOrderWithDeductedInventory_commits() {
        seedSku();
        seedDeviceAndUser("IT-V271-OK");
        tx.executeWithoutResult(st -> {
            insertSession("IT-V271-S1", "IT-V271-OK");
            insertOrder("IT-V271-O1", "IT-V271-S1", "IT-V271-OK", true);
            insertLine("IT-V271-O1");
        }); // commit：触发器校验通过
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM cabinet_order WHERE order_id = 'IT-V271-O1'", Integer.class);
        assertEquals(1, n);
    }

    @Test
    void paidOrderWithoutDeductedInventory_explodesAtCommit_withZeroResidue() {
        seedSku();
        seedDeviceAndUser("IT-V271-NG");
        // DEFERRED：语句期不炸、commit 时炸 ⇒ TransactionTemplate 抛出并回滚
        var ex = assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(st -> {
            insertSession("IT-V271-S2", "IT-V271-NG");
            insertOrder("IT-V271-O2", "IT-V271-S2", "IT-V271-NG", false);
            insertLine("IT-V271-O2");
        }));
        assertTrue(ex.getMessage() != null
                        && (ex.getMessage().contains("inventory_deducted=true")
                            || ex.getMessage().contains("integrity_constraint_violation")
                            || ex.getCause() != null),
                "提交期应触发 V271 触发器违例，实际: " + ex.getMessage());
        // 回滚零残留（铁律 51 口径）：订单/会话行均不应存在
        Integer orders = jdbc.queryForObject(
                "SELECT count(*) FROM cabinet_order WHERE order_id = 'IT-V271-O2'", Integer.class);
        Integer sessions = jdbc.queryForObject(
                "SELECT count(*) FROM shopping_session WHERE session_id = 'IT-V271-S2'", Integer.class);
        assertEquals(0, orders, "违例提交须整体回滚（订单零残留）");
        assertEquals(0, sessions, "违例提交须整体回滚（会话零残留）");
        // 对照：无行订单（has_lines=false ⇒ 触发器放行）不受影响——边界语义钉住
        tx.executeWithoutResult(st -> {
            insertSession("IT-V271-S3", "IT-V271-NG");
            insertOrder("IT-V271-O3", "IT-V271-S3", "IT-V271-NG", false);
            // 无 line：has_lines=false ⇒ 触发器直接放行（保持既有语义）
        });
        Integer bare = jdbc.queryForObject(
                "SELECT count(*) FROM cabinet_order WHERE order_id = 'IT-V271-O3'", Integer.class);
        assertEquals(1, bare);
    }
}