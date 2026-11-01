package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.PromotionActivity;
import com.aicabinet.trade.domain.PromotionPlacement;
import com.aicabinet.trade.mapper.PromotionActivityMapper;
import com.aicabinet.trade.mapper.PromotionPlacementMapper;
import com.aicabinet.trade.service.PromotionPricingService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CB-082 活动定价/展示位 SQL 真库回归。
 *
 * <p>为什么必须真库：rule_config 是 JSONB，数组包含判断最初写成 `?` 运算符，
 * MyBatis 把 `?` 当 JDBC 占位符 ⇒ 线上首次联调直接 500（No value specified for parameter N）。
 * 单测全部 mock 掉 mapper，只有真跑迁移 + 真 SQL 才能拦住这类缺陷。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class PromotionPricingMapperIT {

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
    private PromotionActivityMapper activityMapper;
    @Autowired
    private PromotionPlacementMapper placementMapper;
    @Autowired
    private PromotionPricingService pricingService;

    private static final String DEVICE = "IT-DEV-1";
    private static final String MERCHANT = "MCH-DEFAULT";

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM cabinet_order_line WHERE order_id LIKE 'IT-PROMO-%'");
        jdbc.update("DELETE FROM cabinet_order WHERE order_id LIKE 'IT-PROMO-%'");
        jdbc.update("DELETE FROM shopping_session WHERE session_id LIKE 'IT-PROMO-%'");
        jdbc.update("DELETE FROM device_info WHERE device_id = ?", DEVICE);
        // 只清本 IT 自己的数据：种子库里已有 coupon_definition 引用演示活动，不能整表删
        jdbc.update("DELETE FROM promotion_placement WHERE activity_id IN (SELECT activity_id FROM promotion_activity WHERE activity_name LIKE 'IT-%')");
        jdbc.update("DELETE FROM promotion_activity WHERE activity_name LIKE 'IT-%'");
    }

    private long insertActivity(String name, String type, String scope, String ruleJson) {
        Timestamp start = Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS));
        Timestamp end = Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS));
        Long id = jdbc.queryForObject("""
                INSERT INTO promotion_activity
                  (activity_name, activity_type, status, start_time, end_time, budget_cents,
                   used_cents, user_limit, device_scope, rule_config, description, reduce_cents)
                VALUES (?, ?, 'ACTIVE', ?, ?, 0, 0, 1, ?, ?::jsonb, NULL, 100)
                RETURNING activity_id
                """, Long.class, name, type, start, end, scope, ruleJson);
        return id == null ? -1 : id;
    }

    @Test
    @DisplayName("SKU 命中 + 三种范围（ALL/SPECIFIC/MERCHANT）各自生效，跨商户不命中")
    void findPricedCandidates_scopeAndSku() {
        long all = insertActivity("IT-全量", "SKU_REDUCE", "ALL", "{\"skuIds\":[\"SKU-A\"]}");
        long specific = insertActivity("IT-指定柜", "SKU_DISCOUNT", "SPECIFIC",
                "{\"skuIds\":[\"SKU-A\"],\"deviceIds\":[\"" + DEVICE + "\"]}");
        long merchant = insertActivity("IT-指定商户", "SKU_FIXED_PRICE", "MERCHANT",
                "{\"skuIds\":[\"SKU-A\"],\"merchantIds\":[\"" + MERCHANT + "\"]}");
        insertActivity("IT-范围不符", "SKU_REDUCE", "SPECIFIC",
                "{\"skuIds\":[\"SKU-A\"],\"deviceIds\":[\"OTHER-DEV\"]}");
        insertActivity("IT-SKU不符", "SKU_REDUCE", "ALL", "{\"skuIds\":[\"SKU-B\"]}");

        List<Long> ids = activityMapper.findPricedCandidates(DEVICE, MERCHANT, "SKU-A")
                .stream().map(PromotionActivity::getActivityId).toList();
        assertTrue(ids.containsAll(List.of(all, specific, merchant)), "三类范围都应命中, 实际=" + ids);
        assertEquals(3, ids.size(), "跨柜/跨 SKU 的不得命中, 实际=" + ids);

        List<Long> noMerchant = activityMapper.findPricedCandidates(DEVICE, null, "SKU-A")
                .stream().map(PromotionActivity::getActivityId).toList();
        assertEquals(2, noMerchant.size(), "无商户上下文时 MERCHANT 范围不得命中, 实际=" + noMerchant);
    }

    @Test
    @DisplayName("定价服务真库：立减 100 分 → 350-100=250；每人限 1 单后不再命中")
    void resolveUnitPrice_realDb_numbers() {
        jdbc.update("INSERT INTO device_info (device_id, device_name, merchant_id) VALUES (?, ?, ?)",
                DEVICE, "IT 测试柜", MERCHANT);
        long activityId = insertActivity("IT-立减", "SKU_REDUCE", "ALL", "{\"skuIds\":[\"SKU-A\"]}");
        // userLimit 默认 1；先验证首单命中
        var first = pricingService.resolveUnitPrice(9001L, DEVICE, "SKU-A", 350);
        assertTrue(first.isPresent(), "首单应命中活动");
        assertEquals(250, first.get().unitPriceCents());
        assertEquals(100, first.get().discountPerUnitCents());

        // 造一单已核销（真实 user + session + order + line 指向活动），再查该用户应不命中
        jdbc.update("INSERT INTO user_info (user_id, phone_number) VALUES (9001, '19900009001') ON CONFLICT (user_id) DO NOTHING");
        jdbc.update("""
                INSERT INTO sku_catalog (sku_id, sku_name, price_cents)
                VALUES ('SKU-A', 'IT 测试商品', 350) ON CONFLICT (sku_id) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO shopping_session (session_id, user_id, device_id, state)
                VALUES ('IT-PROMO-S1', 9001, ?, 'COMPLETED')
                """, DEVICE);
        jdbc.update("""
                INSERT INTO cabinet_order (order_id, session_id, user_id, device_id, status)
                VALUES ('IT-PROMO-1', 'IT-PROMO-S1', 9001, ?, 'PAID')
                """, DEVICE);
        jdbc.update("""
                INSERT INTO cabinet_order_line
                  (order_id, sku_id, quantity, unit_price_cents, line_amount_cents, activity_id, activity_discount_cents)
                VALUES ('IT-PROMO-1', 'SKU-A', 1, 250, 250, ?, 100)
                """, activityId);

        assertTrue(pricingService.resolveUnitPrice(9001L, DEVICE, "SKU-A", 350).isEmpty(),
                "每人限 1 单：已核销后同一用户不应再命中");
        assertTrue(pricingService.resolveUnitPrice(9002L, DEVICE, "SKU-A", 350).isPresent(),
                "其他用户仍应命中（副作用不得外溢）");
    }

    @Test
    @DisplayName("展示位查询：同位置取生效活动，范围判定与定价一致")
    void findActivePlacements_scope() {
        long all = insertActivity("IT-全量", "SKU_REDUCE", "ALL", "{\"skuIds\":[\"SKU-A\"]}");
        long specific = insertActivity("IT-指定柜", "SKU_FIXED_PRICE", "SPECIFIC",
                "{\"skuIds\":[\"SKU-A\"],\"deviceIds\":[\"" + DEVICE + "\"]}");
        insertActivity("IT-范围不符", "SKU_REDUCE", "SPECIFIC",
                "{\"skuIds\":[\"SKU-A\"],\"deviceIds\":[\"OTHER-DEV\"]}");

        jdbc.update("INSERT INTO promotion_placement (activity_id, position, asset_id, image_width, image_height) VALUES (?, 'BANNER', 1, 750, 300)", all);
        jdbc.update("INSERT INTO promotion_placement (activity_id, position, asset_id, image_width, image_height) VALUES (?, 'BANNER', 2, 750, 300)", specific);

        List<Long> ids = placementMapper.findActivePlacements(DEVICE, MERCHANT, "BANNER")
                .stream().map(PromotionPlacement::getActivityId).toList();
        assertEquals(List.of(all, specific), ids);
        assertEquals(0, placementMapper.findActivePlacements(DEVICE, MERCHANT, "POPUP_AFTER_CLOSE").size());
    }
}
