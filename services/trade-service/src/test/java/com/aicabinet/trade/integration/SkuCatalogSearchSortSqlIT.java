package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.Instant;
import java.util.List;
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

/**
 * 商品管理「按添加时间排序」的真库实证（2026-10-21）。
 *
 * <p>{@code SkuCatalogMapper.search} 的 ORDER BY 由 MyBatis-Plus lambda 生成，单测里 mapper 是
 * mock ⇒ 排序 SQL 从未被真实数据库执行过。本 IT 钉住：sortDir=asc/desc 时按 created_at
 * 排序且分页 total/records 一致；不传 sortDir 时保持既有 sku_code 升序（其他列表/下拉不受影响）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class SkuCatalogSearchSortSqlIT {

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
    private SkuCatalogMapper skuCatalogMapper;

    @Test
    @DisplayName("sortDir=desc：按添加时间倒序（最新在前），total 不变")
    void desc_ordersByCreatedAtNewestFirst() {
        String prefix = uniquePrefix("DESC");
        Instant now = Instant.now();
        seed(prefix, "A", 990_001, now.minusSeconds(7200));
        seed(prefix, "B", 990_002, now.minusSeconds(3600));
        seed(prefix, "C", 990_003, now.minusSeconds(10));

        Page<SkuCatalog> page = skuCatalogMapper.search(prefix, null, null, null, 0, 20, "desc");

        assertEquals(3L, page.getTotal(), "total 不应随排序方向变化");
        assertEquals(List.of(prefix + "-C", prefix + "-B", prefix + "-A"), ids(page),
                "添加时间倒序失效：最新添加的商品没有排在最前");
    }

    @Test
    @DisplayName("sortDir=asc：按添加时间升序（最早在前）")
    void asc_ordersByCreatedAtOldestFirst() {
        String prefix = uniquePrefix("ASC");
        Instant now = Instant.now();
        seed(prefix, "A", 991_001, now.minusSeconds(7200));
        seed(prefix, "B", 991_002, now.minusSeconds(3600));
        seed(prefix, "C", 991_003, now.minusSeconds(10));

        Page<SkuCatalog> page = skuCatalogMapper.search(prefix, null, null, null, 0, 20, "asc");

        assertEquals(3L, page.getTotal());
        assertEquals(List.of(prefix + "-A", prefix + "-B", prefix + "-C"), ids(page),
                "添加时间升序失效");
    }

    @Test
    @DisplayName("分页在添加时间倒序下仍逐页连续（records 与 total 同口径）")
    void desc_paginatesConsistently() {
        String prefix = uniquePrefix("PAGE");
        Instant now = Instant.now();
        seed(prefix, "A", 992_001, now.minusSeconds(7200));
        seed(prefix, "B", 992_002, now.minusSeconds(3600));
        seed(prefix, "C", 992_003, now.minusSeconds(10));

        Page<SkuCatalog> first = skuCatalogMapper.search(prefix, null, null, null, 0, 2, "desc");
        Page<SkuCatalog> second = skuCatalogMapper.search(prefix, null, null, null, 1, 2, "desc");

        assertEquals(3L, first.getTotal());
        assertEquals(3L, second.getTotal());
        assertEquals(List.of(prefix + "-C", prefix + "-B"), ids(first));
        assertEquals(List.of(prefix + "-A"), ids(second), "第二页应为排序后的最后 1 条");
    }

    @Test
    @DisplayName("不传 sortDir：保持既有 sku_code 升序（其他 skusList 调用不受影响）")
    void nullSortDir_keepsLegacySkuCodeOrder() {
        String prefix = uniquePrefix("LEGACY");
        Instant now = Instant.now();
        seed(prefix, "A", 993_003, now.minusSeconds(10));
        seed(prefix, "B", 993_001, now.minusSeconds(7200));
        seed(prefix, "C", 993_002, now.minusSeconds(3600));

        Page<SkuCatalog> page = skuCatalogMapper.search(prefix, null, null, null, 0, 20, null);

        assertEquals(3L, page.getTotal());
        assertEquals(List.of(prefix + "-B", prefix + "-C", prefix + "-A"), ids(page),
                "sortDir 为空时应按 sku_code 升序（B=993001 → C=993002 → A=993003）");
    }

    @Test
    @DisplayName("类目筛选同时命中字典键值与历史标签（category + categoryLabel 走 IN）")
    void categoryFilter_matchesCodeAndLegacyLabel() {
        String prefix = uniquePrefix("CAT");
        Instant now = Instant.now();
        seed(prefix, "CODE", 994_001, now, "1");
        seed(prefix, "LABEL", 994_002, now, "饮料");
        seed(prefix, "OTHER", 994_003, now, "零食");

        Page<SkuCatalog> page = skuCatalogMapper.search(
                prefix, null, "1", "饮料", null, 0, 20, null);

        assertEquals(2L, page.getTotal(),
                "筛选「饮料」必须同时命中键值行（1）与历史标签行（饮料），否则旧数据筛不出来");
        assertEquals(List.of(prefix + "-CODE", prefix + "-LABEL"), ids(page));
    }

    private String uniquePrefix(String tag) {
        return "IT-SORT-" + tag + "-" + System.nanoTime();
    }

    private void seed(String prefix, String tag, long skuCode, Instant createdAt) {
        seed(prefix, tag, skuCode, createdAt, null);
    }

    private void seed(String prefix, String tag, long skuCode, Instant createdAt, String category) {
        // 名称走 uk_sku_catalog_name_spec（lower(trim(name)), lower(trim(spec))）⇒ 必须带本用例唯一前缀
        jdbc.update("INSERT INTO sku_catalog (sku_id, sku_code, sku_name, price_cents, created_at, category)"
                        + " VALUES (?, ?, ?, 100, ?, ?)",
                prefix + "-" + tag, skuCode, "排序 IT 商品 " + prefix + " " + tag,
                java.sql.Timestamp.from(createdAt), category);
    }

    private List<String> ids(Page<SkuCatalog> page) {
        return page.getRecords().stream().map(SkuCatalog::getSkuId).toList();
    }
}
