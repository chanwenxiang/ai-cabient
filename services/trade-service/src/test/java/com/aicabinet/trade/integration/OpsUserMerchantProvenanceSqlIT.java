package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.OpsUserMerchant;
import com.aicabinet.trade.mapper.OpsUserMerchantMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V410 真库回归（G2-11 §7.17 ⑨）：{@code ops_user_merchant} 的 created_at / created_by。
 *
 * <p>为什么必须走真库：这两列的三条承诺全都在 Mockito 看不见的地方——
 * ① {@code NOT NULL DEFAULT NOW()} 是否真的成立（决定「既有行回填成迁移时刻」这句话是不是真的）；
 * ② {@code @TableField(fill = FieldFill.INSERT)} 是否真的被 {@code MybatisMetaObjectHandler} 接上
 * （写错注解不会报错，只会静默留 null，而 DB 默认值恰好会把它填上 ⇒ 单测与查询都看不出差别）；
 * ③ 中文注释是否真落进 {@code pg_description}（库里读不到注释=运维侧口径丢失）。
 * 本 IT 由 Spring Boot 在**空库**上跑完整 Flyway 链（test profile 的 flyway.enabled=true），
 * 所以顺带钉住「V410 能从零升上来」这条。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class OpsUserMerchantProvenanceSqlIT {

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

    private static final long GRANTOR = 7000000410L;
    private static final long TARGET = 7000000411L;
    private static final String MERCHANT_A = "MCH-IT-V410-A";
    private static final String MERCHANT_B = "MCH-IT-V410-B";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OpsUserMerchantMapper mapper;

    @BeforeEach
    void seedParents() {
        // 两列都有 FK（user_id→user_info 由 V218 加、merchant_id→merchant 由 V16 建表即带）⇒ 先播父行
        jdbc.update("INSERT INTO user_info (user_id, phone_number) VALUES (?, '13900741000') "
                + "ON CONFLICT (user_id) DO NOTHING", GRANTOR);
        jdbc.update("INSERT INTO user_info (user_id, phone_number) VALUES (?, '13900741001') "
                + "ON CONFLICT (user_id) DO NOTHING", TARGET);
        // merchant_name 上有 uk_merchant_name 唯一约束 ⇒ 名必须逐行不同
        jdbc.update("INSERT INTO merchant (merchant_id, merchant_name, remark) "
                + "VALUES (?, 'V410 IT 商户甲', 'OpsUserMerchantProvenanceSqlIT') "
                + "ON CONFLICT (merchant_id) DO NOTHING", MERCHANT_A);
        jdbc.update("INSERT INTO merchant (merchant_id, merchant_name, remark) "
                + "VALUES (?, 'V410 IT 商户乙', 'OpsUserMerchantProvenanceSqlIT') "
                + "ON CONFLICT (merchant_id) DO NOTHING", MERCHANT_B);
        jdbc.update("DELETE FROM ops_user_merchant WHERE user_id IN (?, ?)", GRANTOR, TARGET);
    }

    private Map<String, Object> columnShape(String column) {
        return jdbc.queryForMap(
                "SELECT is_nullable, data_type, coalesce(column_default, '') AS dft"
                        + "  FROM information_schema.columns"
                        + " WHERE table_name = 'ops_user_merchant' AND column_name = ?",
                column);
    }

    @Test
    void createdAtIsNotNullTimestampWithNowDefault() {
        Map<String, Object> shape = columnShape("created_at");
        assertEquals("timestamp with time zone", shape.get("data_type"));
        assertEquals("NO", shape.get("is_nullable"));
        assertTrue(String.valueOf(shape.get("dft")).contains("now()"),
                "缺 DEFAULT now() ⇒ 既有行不会回填，V410 头注释那句「回填为迁移执行时刻」不成立：" + shape);
    }

    @Test
    void createdByIsNullableBigintWithoutForeignKeyByDesign() {
        Map<String, Object> shape = columnShape("created_by");
        assertEquals("bigint", shape.get("data_type"));
        assertEquals("YES", shape.get("is_nullable"));
        // 🔴 刻意的不一致（V410 头注释给了理由）：created_by 是取证列，加外键会让删操作人
        // 要么被挡（RESTRICT）要么把取证抹掉（SET NULL）。要改请先改那段理由，别顺手对齐 V220。
        Integer fks = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint c"
                        + "  JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)"
                        + " WHERE c.conrelid = 'ops_user_merchant'::regclass"
                        + "   AND c.contype = 'f' AND a.attname = 'created_by'",
                Integer.class);
        assertEquals(0, fks, "created_by 被加了外键 ⇒ 与 V410 的取证语义冲突");
    }

    @Test
    void bothNewColumnsCarryTheirMigrationComment() {
        for (String column : new String[] {"created_at", "created_by"}) {
            String comment = jdbc.queryForObject(
                    "SELECT col_description('ops_user_merchant'::regclass, ordinal_position)"
                            + "  FROM information_schema.columns"
                            + " WHERE table_name = 'ops_user_merchant' AND column_name = ?",
                    String.class, column);
            assertNotNull(comment, column + " 没有注释 ⇒ 运维侧读不出语义");
            assertTrue(comment.contains("V410"), column + " 注释未标来源迁移：" + comment);
        }
    }

    /** 裸 SQL 插入（= 迁移前既有行 / 系统种子路径的形状）：created_at 由 DB 默认兜底、created_by 留 NULL。 */
    @Test
    void rawInsertWithoutTheNewColumnsStillGetsCreatedAtFromTheDefault() {
        jdbc.update("INSERT INTO ops_user_merchant (user_id, merchant_id) VALUES (?, ?)", TARGET, MERCHANT_A);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT created_at, created_by FROM ops_user_merchant"
                        + " WHERE user_id = ? AND merchant_id = ?", TARGET, MERCHANT_A);
        assertNotNull(row.get("created_at"));
        assertNull(row.get("created_by"));
    }

    /**
     * 应用侧插入必须把 created_at 填到**实体**上（判据是实体，不是库——库里那一列有默认值兜底，
     * 光读库分不出是 MP 填的还是 DB 填的 ⇒ 注解写错也能绿）。
     */
    @Test
    void mapperInsertFillsCreatedAtOnEntityAndPersistsTheGrantor() {
        OpsUserMerchant binding = new OpsUserMerchant(TARGET, MERCHANT_B);
        binding.setCreatedBy(GRANTOR);

        mapper.insert(binding);

        assertNotNull(binding.getCreatedAt(),
                "MybatisMetaObjectHandler 没接上 ⇒ @TableField(fill = FieldFill.INSERT) 写错了");
        Long persistedGrantor = jdbc.queryForObject(
                "SELECT created_by FROM ops_user_merchant WHERE user_id = ? AND merchant_id = ?",
                Long.class, TARGET, MERCHANT_B);
        assertEquals(GRANTOR, persistedGrantor);
    }
}
