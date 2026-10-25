package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.CouponDefinition;
import com.aicabinet.trade.mapper.CouponDefinitionMapper;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * G6/§7.24.1 真库回归：{@code coupon_definition.scope_device_ids VARCHAR(64)[]} 的读写映射。
 * <p>修复前 {@code String[] scopeDeviceIds} 无 typeHandler，带柜机范围的券定义 insert 直接
 * {@code Type handler was null on parameter mapping} 500（UAT §7.24.1 实测）。
 * 单测（Mockito）拦不住这类问题——本 IT 走真库钉住 insert/selectById/updateById 三链。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class CouponScopeDeviceIdsSqlIT {

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
    private CouponDefinitionMapper couponDefinitionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static CouponDefinition deviceScoped(String name, String[] deviceIds) {
        CouponDefinition def = new CouponDefinition();
        def.setCouponName(name);
        def.setCouponType("DISCOUNT");
        def.setDenominationCents(500);
        def.setScopeType("DEVICE");
        def.setScopeDeviceIds(deviceIds);
        return def;
    }

    @Test
    void insert_selectById_updateById_roundTripRealVarcharArray() {
        CouponDefinition def = deviceScoped("G6-数组映射回归", new String[]{"CAB-A01", "CAB-A02"});

        // 修复前此处已 500（Type handler was null ... 'scopeDeviceIds'）
        couponDefinitionMapper.insert(def);
        assertNotNull(def.getCouponDefId(), "insert 后主键应回填");

        // 真列型别：必须是 PG varchar[]，不是把 "{a,b}" 当字符串塞进 text 列
        String columnType = jdbcTemplate.queryForObject(
                "SELECT pg_typeof(scope_device_ids)::text FROM coupon_definition WHERE coupon_def_id = ?",
                String.class, def.getCouponDefId());
        assertEquals("character varying[]", columnType);
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT array_length(scope_device_ids, 1) FROM coupon_definition WHERE coupon_def_id = ?",
                Integer.class, def.getCouponDefId()));

        CouponDefinition loaded = couponDefinitionMapper.selectById(def.getCouponDefId());
        assertNotNull(loaded, "selectById 应命中");
        assertEquals("DEVICE", loaded.getScopeType());
        assertArrayEquals(new String[]{"CAB-A01", "CAB-A02"}, loaded.getScopeDeviceIds(),
                "读回应为数组元素，而非 \"{CAB-A01,CAB-A02}\" 字面量");

        loaded.setScopeDeviceIds(new String[]{"CAB-B09"});
        couponDefinitionMapper.updateById(loaded);
        CouponDefinition reloaded = couponDefinitionMapper.selectById(def.getCouponDefId());
        assertArrayEquals(new String[]{"CAB-B09"}, reloaded.getScopeDeviceIds(), "updateById 应覆盖数组");

        couponDefinitionMapper.deleteById(def.getCouponDefId());
    }

    @Test
    void emptyAndNullScopeDeviceIds_areSafe() {
        CouponDefinition empty = deviceScoped("G6-空数组", new String[0]);
        couponDefinitionMapper.insert(empty);
        assertEquals(0, couponDefinitionMapper.selectById(empty.getCouponDefId())
                .getScopeDeviceIds().length, "空数组读写后仍为空集（null 列值 → EMPTY 语义）");

        CouponDefinition nullIds = deviceScoped("G6-null 数组", null);
        couponDefinitionMapper.insert(nullIds);
        assertNull(couponDefinitionMapper.selectById(nullIds.getCouponDefId()).getScopeDeviceIds(),
                "null 列值读回 null（与空数组区分）");

        couponDefinitionMapper.deleteById(empty.getCouponDefId());
        couponDefinitionMapper.deleteById(nullIds.getCouponDefId());
    }
}
