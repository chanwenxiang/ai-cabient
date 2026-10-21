package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.PullOffTask;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.service.ReplenishmentService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.Instant;
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
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 临期页签「按设备下钻」的<b>真库实证</b>（2026-10-21）。
 *
 * <p>{@code PullOffTaskMapper.searchPage} 的过滤是 MyBatis-Plus 条件式
 * {@code .eq(dev != null, PullOffTask::getDeviceId, dev)} 生成的 WHERE，单测里 mapper 是 mock
 * ⇒ 这条 SQL 从未被任何数据库执行过（同 {@link WithdrawClaimForPayoutSqlIT} 的口径）。
 * 而线上症状正是「total 不随过滤收窄」：旧实现拉回一页再前端 filter，分页元数据仍是全量，
 * 从设备详情下钻时「显示 N 条 / 共 M 条」不符、翻页大面积空。
 * 本 IT 用真 PostgreSQL 把「过滤后 records 与 total <b>同步</b>收窄」钉成硬约束。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class PullOffTaskDeviceFilterSqlIT {

    private static final String SKU = "SKU-IT-PULLOFF";

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
    private PullOffTaskMapper pullOffTaskMapper;
    @Autowired
    private ReplenishmentService replenishmentService;

    @Test
    @DisplayName("deviceId 非空：records 与 total 同步收窄为该设备的行数")
    void deviceIdFilter_narrowsRecordsAndTotalTogether() {
        String devA = seedDevice("A");
        String devB = seedDevice("B");
        seedTask(devA, "OPEN", 3, null);
        seedTask(devB, "OPEN", 2, null);

        Page<PullOffTask> page = pullOffTaskMapper.searchPage("OPEN", devA, 0, 20);

        assertEquals(3L, page.getTotal(),
                "total 没随 deviceId 收窄 ⇒ WHERE 没进 SQL，前端拿到的仍是全量分页元数据");
        assertEquals(3, page.getRecords().size());
        assertTrue(page.getRecords().stream().allMatch(t -> devA.equals(t.getDeviceId())),
                "过滤后仍返回了别的设备的行");
    }

    @Test
    @DisplayName("deviceId 为 null 或空白：保持全量口径（不能把过滤加反）")
    void blankDeviceId_keepsFullScope() {
        String devC = seedDevice("C");
        long baseline = countAll();
        seedTask(devC, "OPEN", 2, null);
        long expected = baseline + 2;

        Page<PullOffTask> nullFilter =
                pullOffTaskMapper.searchPage("OPEN", null, 0, 100);
        Page<PullOffTask> blankFilter =
                pullOffTaskMapper.searchPage("OPEN", "   ", 0, 100);

        assertEquals(expected, nullFilter.getTotal(),
                "deviceId=null 应不过滤：total 必须等于播种后的全量行数");
        assertEquals(nullFilter.getTotal(), blankFilter.getTotal(),
                "空白 deviceId 应与 null 同口径（服务层传的是 trim 后的字符串）");
        assertTrue(blankFilter.getRecords().stream().anyMatch(t -> devC.equals(t.getDeviceId())),
                "全量口径里看不到本用例播种的行");
    }

    @Test
    @DisplayName("status 与 created_at 倒序不受 deviceId 过滤影响")
    void statusAndOrdering_arePreserved() {
        String devD = seedDevice("D");
        Instant now = Instant.now();
        seedTask(devD, "OPEN", 1, now.minusSeconds(3600));
        seedTask(devD, "OPEN", 1, now.minusSeconds(7200));
        seedTask(devD, "RESOLVED", 1, now.minusSeconds(10));

        Page<PullOffTask> page = pullOffTaskMapper.searchPage("OPEN", devD, 0, 20);
        assertEquals(2L, page.getTotal(), "非 OPEN 的行被算进来了");
        assertEquals(2, page.getRecords().size());
        assertTrue(page.getRecords().stream().allMatch(t -> "OPEN".equals(t.getStatus())));
        // 较新的一条在前（下钻列表按创建时间倒序）
        assertTrue(page.getRecords().get(0).getCreatedAt().isAfter(page.getRecords().get(1).getCreatedAt()),
                "created_at 倒序失效：" + page.getRecords().get(0).getCreatedAt()
                        + " vs " + page.getRecords().get(1).getCreatedAt());

        // 状态空白时 mapper 自己兜到 OPEN，行为与显式 "OPEN" 一致
        assertEquals(page.getTotal(), pullOffTaskMapper.searchPage("", devD, 0, 20).getTotal(),
                "status 空白的兜底口径变了");
    }

    @Test
    @DisplayName("服务层分页：翻页落在过滤集内（旧前端过滤会在第二页大面积空）")
    void servicePage_paginatesWithinFilteredRows() {
        String devE = seedDevice("E");
        String devF = seedDevice("F");
        seedTask(devE, "OPEN", 4, null);
        seedTask(devF, "OPEN", 5, null);

        var first = replenishmentService.listOpenPullOffTasksPage(devE, 0, 3);
        assertEquals(4L, first.total(), "服务层 total 不是过滤后的行数");
        assertEquals(3, first.items().size());
        assertTrue(first.items().stream().allMatch(d -> devE.equals(d.deviceId())));

        var second = replenishmentService.listOpenPullOffTasksPage(devE, 1, 3);
        assertEquals(4L, second.total());
        assertEquals(1, second.items().size(),
                "第二页应恰有过滤集里的最后 1 条；0 条 ⇒ 分页仍按全量算偏移");
    }

    /** 播种一台设备（pull_off_task.device_id 有 FK → device_info，建表迁移里看不到）。 */
    private String seedDevice(String tag) {
        String deviceId = "IT-PULLOFF-" + tag + "-" + System.nanoTime();
        jdbc.update("INSERT INTO device_info (device_id, device_name) VALUES (?, ?)", deviceId, tag);
        jdbc.update("INSERT INTO sku_catalog (sku_id, sku_name, price_cents) VALUES (?, ?, 100) "
                + "ON CONFLICT (sku_id) DO NOTHING", SKU, "抢权 IT 商品");
        return deviceId;
    }

    private void seedTask(String deviceId, String status, int count, Instant createdAt) {
        for (int i = 0; i < count; i++) {
            if (createdAt == null) {
                jdbc.update("INSERT INTO pull_off_task (device_id, sku_id, reason, status)"
                        + " VALUES (?, ?, 'NEAR_EXPIRY', ?)", deviceId, SKU, status);
            } else {
                // PG JDBC 对 java.time.Instant 裸占位符报「Can't infer the SQL type」⇒ 传 Timestamp
                jdbc.update("INSERT INTO pull_off_task (device_id, sku_id, reason, status, created_at)"
                                + " VALUES (?, ?, 'NEAR_EXPIRY', ?, ?)",
                        deviceId, SKU, status, java.sql.Timestamp.from(createdAt));
            }
        }
    }

    private long countAll() {
        return jdbc.queryForObject("SELECT count(*) FROM pull_off_task WHERE status = 'OPEN'", Long.class);
    }
}
