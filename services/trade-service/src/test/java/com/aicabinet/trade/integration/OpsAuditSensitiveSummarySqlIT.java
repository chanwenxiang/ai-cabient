package com.aicabinet.trade.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import com.aicabinet.common.dto.AdminAuditSensitiveSummaryDto;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.service.OpsAuditQueryService;
import com.aicabinet.trade.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * 敏感操作汇总在**真 Postgres**上的行为（活栈 500，追踪号 fb841eb1070d，2026-10-26）。
 *
 * <p>既有 {@code OpsAuditSensitiveSummaryTest} 桩的是 {@code JdbcTemplate}：mock 直接返回造好的 DTO，
 * 所以 {@code RowMapper} 那三行**从来没被执行过**。而缺陷恰在 RowMapper 里
 * （pgjdbc 42.7.12 没注册 {@code timestamptz → Instant} 转换，`rs.getObject(col, Instant.class)`
 * 在 {@code MAX(created_at)} 上恒抛 {@code conversion to class java.time.Instant from
 * timestamptz not supported}）⇒ 只有把真数据喂给真库、跑真驱动才算证明。
 *
 * <p>权限腿仍交 mock（那条腿由单测钉住），本 IT 只把 SQL 腿换成真库。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class OpsAuditSensitiveSummarySqlIT {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

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

    private OpsAuditQueryService service;

    @BeforeEach
    void setUp() {
        service = new OpsAuditQueryService(mock(PermissionService.class),
                mock(AdminAuditLogMapper.class), mock(UserInfoMapper.class), jdbc);
        jdbc.update("DELETE FROM admin_audit_log");
        jdbc.update("INSERT INTO user_info (user_id, phone_number) VALUES (700009, '13900700009') "
                + "ON CONFLICT (user_id) DO NOTHING");
    }

    /** created_at 是 timestamptz NOT NULL（默认 now()），这里显式给值以钉窗口与「最近一次」。 */
    private void seedAudit(String action, Instant createdAt) {
        jdbc.update("INSERT INTO admin_audit_log (operator_id, action, target_type, target_id, detail, created_at) "
                + "VALUES (700009, ?, 'SKU', 'IT-SENSITIVE', 'sensitive-summary-it', ?)",
                action, Timestamp.from(createdAt));
    }

    private Optional<AdminAuditSensitiveSummaryDto> rowOf(List<AdminAuditSensitiveSummaryDto> rows, String action) {
        return rows.stream().filter(r -> r.action().equals(action)).findFirst();
    }

    @Test
    @DisplayName("敏感汇总在真库上返回行，且 MAX(created_at) 能读成 Instant 且等于库里的真值")
    void sensitiveSummaryReadsTimestamptzOnRealDb() {
        // 截到秒：PG timestamptz 存微秒，Instant.now() 带纳秒 ⇒ 不截会把「等于库里真值」写成必败断言
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        seedAudit("CONFIG_UPSERT", base.minus(2, ChronoUnit.DAYS));
        seedAudit("CONFIG_UPSERT", base.minus(60, ChronoUnit.SECONDS));
        seedAudit("OPS_ROLE_CREATE", base.minus(3, ChronoUnit.DAYS));
        seedAudit("BALANCE_ADJUST", base.minus(40, ChronoUnit.DAYS));

        List<AdminAuditSensitiveSummaryDto> rows = service.sensitiveSummary(700009L, 7);

        assertFalse(rows.isEmpty(),
                "真库上必须取到行——本方法在活栈恒 500 就是死在 RowMapper 的类型映射（追踪号 fb841eb1070d）");
        assertEquals(2, rows.size(), "40 天前的 BALANCE_ADJUST 应被 7 天窗口挡在外面");

        AdminAuditSensitiveSummaryDto config = rowOf(rows, "CONFIG_UPSERT").orElseThrow();
        assertEquals(2L, config.total());
        assertNotNull(config.lastAt(), "lastAt 不得为 null：MAX(created_at) 有行就一定有值");

        // 断言读库，不抄 SQL：期望值由数据库自己给（同一列按 Timestamp 读，绕开被测的转换腿）
        Instant dbTruth = jdbc.queryForObject(
                "SELECT MAX(created_at) FROM admin_audit_log WHERE action = 'CONFIG_UPSERT'", Timestamp.class)
                .toInstant();
        assertEquals(dbTruth, config.lastAt(),
                "汇总里的「最近一次」必须等于库里的真值（等价于按 JVM 时区偏移读数的那种「看着对」的错写法要被抓出）");
        assertEquals(base.minus(60, ChronoUnit.SECONDS), config.lastAt(),
                "绝对时刻不得被时区平移");

        assertTrue(rowOf(rows, "OPS_ROLE_CREATE").isPresent(), "OPS_ 族前缀要在真库上命中");
    }

    @Test
    @DisplayName("非敏感族 action 不得进汇总（前缀白名单在真库上生效，不是只在 mock 参数里）")
    void sensitiveSummaryExcludesNonSensitiveActions() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        seedAudit("DEVICE_LIST", base.minus(1, ChronoUnit.HOURS));
        seedAudit("DEVICE_REMOTE_OPEN", base.minus(2, ChronoUnit.HOURS));

        List<AdminAuditSensitiveSummaryDto> rows = service.sensitiveSummary(700009L, 7);

        assertEquals(List.of("DEVICE_REMOTE_OPEN"), rows.stream().map(AdminAuditSensitiveSummaryDto::action).toList(),
                "只有命中族前缀的 action 进报表；DEVICE_LIST 与 DEVICE_REMOTE_OPEN 只差几个字符，前缀口径写错就会混进来");
    }
}
