package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.WarehouseInTransit;
import com.aicabinet.trade.domain.WarehouseOutbound;
import com.aicabinet.trade.mapper.WarehouseInTransitMapper;
import com.aicabinet.trade.mapper.WarehouseOutboundMapper;
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
 * D28-B5 的<b>真库实证</b>：出库状态筛选 / 在途超时筛选必须下推 SQL。
 *
 * <p>症状原型：列表是服务端分页，旧实现只在当前页客户端过滤 ⇒ 过滤后 {@code total} 仍是全量、
 * 翻页大面积空（同 {@link PullOffTaskDeviceFilterSqlIT} 的口径）。本 IT 把「过滤后 records 与
 * total <b>同步</b>收窄」钉成硬约束，并锁住 status 大小写归一与 null/空白＝不过滤的旧行为。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class WarehouseOutboundTransitFilterSqlIT {

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
    private WarehouseOutboundMapper outboundMapper;
    @Autowired
    private WarehouseInTransitMapper transitMapper;

    @Test
    @DisplayName("出库 status 过滤：records 与 total 同步收窄；小写入参归一为大写")
    void outboundStatusFilter_narrowsRecordsAndTotal() {
        String whA = seedWarehouse("A");
        String whB = seedWarehouse("B");
        seedOutbound(whA, "DRAFT");
        seedOutbound(whA, "DRAFT");
        seedOutbound(whA, "PICKED");
        seedOutbound(whB, "DRAFT");

        Page<WarehouseOutbound> draftA = outboundMapper.searchPage(null, whA, "DRAFT", 0, 20);
        assertEquals(2L, draftA.getTotal(),
                "total 没随 status 收窄 ⇒ WHERE 没进 SQL，前端分页元数据仍是全量");
        assertTrue(draftA.getRecords().stream().allMatch(o -> "DRAFT".equals(o.getStatus())),
                "过滤后仍返回了别的状态的行");

        assertEquals(draftA.getTotal(), outboundMapper.searchPage(null, whA, "draft", 0, 20).getTotal(),
                "小写入参应归一为大写后同口径");

        Page<WarehouseOutbound> allA = outboundMapper.searchPage(null, whA, null, 0, 20);
        assertEquals(3L, allA.getTotal(), "status=null 应保持全仓口径（不能把过滤加反）");
        assertEquals(allA.getTotal(), outboundMapper.searchPage(null, whA, "  ", 0, 20).getTotal(),
                "空白 status 应与 null 同口径");
    }

    @Test
    @DisplayName("在途超时过滤：created_at < 截止 与服务端分页 total 同步收窄")
    void transitOverdueFilter_narrowsRecordsAndTotal() {
        String wh = seedWarehouse("T");
        long outboundId = seedOutbound(wh, "SHIPPED");
        String dev = "IT-TRANSIT-" + System.nanoTime();
        Instant now = Instant.now();
        seedTransit(outboundId, dev, now.minusSeconds(25 * 3600));
        seedTransit(outboundId, dev, now.minusSeconds(26 * 3600));
        seedTransit(outboundId, dev, now.minusSeconds(3600));

        Instant cutoff = now.minusSeconds(24 * 3600);
        Page<WarehouseInTransit> overdue = transitMapper.searchPage(dev, "IN_TRANSIT", cutoff, 0, 20);
        assertEquals(2L, overdue.getTotal(),
                "total 没随超时截止收窄 ⇒ lt(createdAt) 没进 SQL");
        assertTrue(overdue.getRecords().stream().allMatch(r -> r.getCreatedAt().isBefore(cutoff)),
                "过滤后仍返回了未超时的行");

        Page<WarehouseInTransit> all = transitMapper.searchPage(dev, "IN_TRANSIT", null, 0, 20);
        assertEquals(3L, all.getTotal(), "createdBefore=null 应保持全量口径");
    }

    private String seedWarehouse(String tag) {
        String id = "IT-D28-WH-" + tag + "-" + System.nanoTime();
        jdbc.update("INSERT INTO warehouse (warehouse_id, warehouse_name) VALUES (?, ?)", id, "D28 IT 仓 " + tag);
        return id;
    }

    private long seedOutbound(String warehouseId, String status) {
        return jdbc.queryForObject(
                "INSERT INTO warehouse_outbound (warehouse_id, status) VALUES (?, ?) RETURNING outbound_id",
                Long.class, warehouseId, status);
    }

    private void seedTransit(long outboundId, String deviceId, Instant createdAt) {
        jdbc.update("INSERT INTO sku_catalog (sku_id, sku_name, price_cents) VALUES ('SKU-IT-TRANSIT', 'D28 IT 商品', 100)"
                + " ON CONFLICT (sku_id) DO NOTHING");
        jdbc.update("INSERT INTO device_info (device_id, device_name) VALUES (?, 'D28 IT 柜')"
                + " ON CONFLICT (device_id) DO NOTHING", deviceId);
        // PG JDBC 对 java.time.Instant 裸占位符报「Can't infer the SQL type」⇒ 传 Timestamp
        jdbc.update("INSERT INTO warehouse_in_transit (outbound_id, device_id, sku_id, batch_no, quantity, status, created_at)"
                        + " VALUES (?, ?, 'SKU-IT-TRANSIT', 'B-IT', 1, 'IN_TRANSIT', ?)",
                outboundId, deviceId, java.sql.Timestamp.from(createdAt));
    }
}
