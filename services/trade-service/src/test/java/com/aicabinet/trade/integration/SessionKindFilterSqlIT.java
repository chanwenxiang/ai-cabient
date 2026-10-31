package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.service.DeviceValidationService;
import com.aicabinet.trade.support.SessionKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 运营会话列表「类型」筛选的<b>真库 + 同源</b>实证（MA-007，2026-10-31 R2）。
 *
 * <p>缺陷原貌：类型筛选在前端对<b>当前页</b>做 {@code rows.filter(...)}，并把 {@code total}
 * 换成页内过滤后的条数 ⇒ 跨页数据被吃掉、分页元数据谎报（实测「已完成+消费」显 13、真值 17）。
 * 修复＝筛选进 {@link ShoppingSessionMapper.SessionFilterCriteria#sessionKind()}。
 *
 * <p>本 IT 钉两件事：
 * ① SQL 过滤出的行，其 Java 判定 {@code DeviceValidationService.sessionKind} 必须逐个等于所请求的
 * 类型（<b>两套口径同源</b>，铁律 16——列表筛出来的和行上显示的不许分叉）；
 * ② {@code total} 与 records 同步收窄（同 {@link PullOffTaskDeviceFilterSqlIT} 的口径）。
 * 并覆盖优先级边界：OPS &gt; RESTOCK &gt; STOCKTAKE &gt; CONSUMER，以及幂等键为 NULL 的行
 * 必须留在 CONSUMER 里（SQL 里 {@code NOT LIKE} 对 NULL 求值为 NULL，写错就会把它们整批判掉）。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class SessionKindFilterSqlIT {

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
    private ShoppingSessionMapper sessionMapper;

    private String seedSession(String sessionId, String idempotencyKey, boolean withTask) {
        String device = "DEV-IT-KIND";
        jdbc.update("INSERT INTO device_info (device_id) VALUES (?) ON CONFLICT DO NOTHING", device);
        jdbc.update("INSERT INTO user_info (user_id, phone_number) VALUES (9000000001, '19000000001') "
                + "ON CONFLICT DO NOTHING");
        Long taskId = null;
        if (withTask) {
            // replenishment_task_id 是 bigint 且有外键 ⇒ 必须先有一条真任务（只 device_id 必填）
            // task_id 走序列默认值 ⇒ INSERT ... RETURNING 拿真主键（列名 task_no 不存在，别猜）
            taskId = jdbc.queryForObject(
                    "INSERT INTO replenishment_task (device_id, status) VALUES (?, 'OPEN') RETURNING task_id",
                    Long.class, device);
        }
        // JdbcTemplate 推不出 java.time.Instant 的 SQL 类型 ⇒ 用 Timestamp（仓内既有写法一致）
        java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO shopping_session (session_id, user_id, device_id, state, created_at, updated_at,"
                + " idempotency_key, replenishment_task_id) VALUES (?, 9000000001, ?, 'COMPLETED', ?, ?, ?, ?)",
                sessionId, device, now, now, idempotencyKey, taskId);
        return sessionId;
    }

    private List<ShoppingSession> filterByKind(String kind) {
        return sessionMapper.findByFiltersOrderByUpdatedAtDesc(
                new ShoppingSessionMapper.SessionFilterCriteria(
                        null, null, null, null, null, null, null, null, null, null, kind),
                PageRequest.of(0, 100)).getContent();
    }

    @Test
    @DisplayName("四种类型各筛各的：SQL 过滤结果与 Java sessionKind 判定逐个一致，且 total 同步收窄")
    void kindFilter_matchesJavaDerivation_andNarrowsTotal() {
        seedSession("S-CONSUMER", null, false);
        seedSession("S-CONSUMER-OTHER-KEY", "PAY_NOTIFY:1", false);
        seedSession("S-RESTOCK-KEY", SessionKind.RESTOCK_KEY_PREFIX + "1", false);
        seedSession("S-RESTOCK-TASK", null, true);
        seedSession("S-OPS", SessionKind.OPS_REMOTE_KEY_PREFIX + "1", true);
        seedSession("S-STOCKTAKE", SessionKind.STOCKTAKE_KEY_PREFIX + "1", false);
        // 边界：盘点键 + 补货任务 ⇒ 按优先级判成 RESTOCK（不是 STOCKTAKE）
        seedSession("S-PRIORITY", SessionKind.STOCKTAKE_KEY_PREFIX + "2", true);

        for (String kind : List.of(SessionKind.CONSUMER, SessionKind.RESTOCK,
                SessionKind.OPS, SessionKind.STOCKTAKE)) {
            List<ShoppingSession> rows = filterByKind(kind);
            long javaSide = sessionMapper.selectList(null).stream()
                    .filter(s -> kind.equals(DeviceValidationService.sessionKind(s)))
                    .count();
            org.junit.jupiter.api.Assertions.assertFalse(rows.isEmpty(), kind + " 一行都没筛出来：WHERE 没进 SQL");
            assertEquals(javaSide, rows.size(),
                    kind + "：SQL 过滤行数与 Java 判定行数不等 ⇒ 两套口径分叉（铁律 16）");
            for (ShoppingSession s : rows) {
                assertEquals(kind, DeviceValidationService.sessionKind(s),
                        "筛 " + kind + " 却返回了 " + s.getSessionId() + "（行上显示的类型会是另一个）");
            }
        }

        // 两个用例共用同一 Testcontainers 库 ⇒ 断言相对全表行数，不写死 7（方法执行顺序不该影响结论）
        long tableTotal = sessionMapper.selectList(null).size();
        assertEquals(tableTotal, filterByKind(null).size(), "kind=null 必须不过滤（全表行数）");
        assertEquals(tableTotal, filterByKind("   ").size(), "空白 kind 与 null 同口径");
        assertEquals(tableTotal, filterByKind(SessionKind.CONSUMER).size()
                + filterByKind(SessionKind.RESTOCK).size()
                + filterByKind(SessionKind.OPS).size()
                + filterByKind(SessionKind.STOCKTAKE).size(),
                "四类之和必须等于全表：有行落在四类之外说明 SQL 与 Java 判定不完备");
    }

    @Test
    @DisplayName("幂等键为 NULL 的行不能被 NOT LIKE 吃掉（它们就是 CONSUMER 的主体）")
    void nullIdempotencyKey_staysInConsumer() {
        seedSession("S-NULL-KEY-A", null, false);
        seedSession("S-NULL-KEY-B", null, false);

        List<String> consumerIds = filterByKind(SessionKind.CONSUMER).stream()
                .map(ShoppingSession::getSessionId).toList();

        org.junit.jupiter.api.Assertions.assertTrue(consumerIds.contains("S-NULL-KEY-A")
                        && consumerIds.contains("S-NULL-KEY-B"),
                "幂等键为 NULL 的会话被类型筛掉了：consumerIds=" + consumerIds);
    }
}
