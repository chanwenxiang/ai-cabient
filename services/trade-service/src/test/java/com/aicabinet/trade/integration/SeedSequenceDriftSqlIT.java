package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.OpsRole;
import com.aicabinet.trade.mapper.OpsRoleMapper;
import java.util.ArrayList;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 种子迁移写完显式主键却不推序列 ⇒ 从零建库后 {@code nextval} 会把<b>已占用</b>的号发出去。
 * 真库跑过两例：{@code operator_user_id_seq}（V47 只校准那一刻，V116/V117/V140 之后又滞后，
 * 叠加 {@code save()} 的 upsert 语义变成静默覆盖在用账号，V398 修）、
 * {@code ops_role_role_id_seq}（V140/V162 显式插 10/11，界面建第 2、3 个角色必 500，V399 修）。
 *
 * <p>本 IT 是这一族的<b>常驻闸门</b>：在真·从零的库上逐序列断言「下一个号必然没被占」，
 * 再真插 3 个角色把「界面建角色」那条路径钉住。以后任何迁移用显式主键插种子而漏了 setval，
 * 这里直接红，而不是等到某个环境上线时炸在建号上。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class SeedSequenceDriftSqlIT {

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
    @Autowired
    private OpsRoleMapper roleMapper;

    /** 序列宿主列（OwnedSequence 由 pg_depend deptype='a' 给出，全是库内目录名，非外部输入）。 */
    private record SeqOwner(String table, String column, String sequence) {}

    private List<SeqOwner> ownedSequences() {
        return jdbc.query(
                """
                SELECT dep.refobjid::regclass::text AS tbl, a.attname AS col, c.oid::regclass::text AS seq
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_depend dep ON dep.objid = c.oid AND dep.deptype = 'a'
                  JOIN pg_attribute a ON a.attrelid = dep.refobjid AND a.attnum = dep.refobjsubid
                 WHERE c.relkind = 'S' AND n.nspname = 'public'
                   AND a.atthasmissing = false AND NOT a.attisdropped
                 ORDER BY 1, 2
                """,
                (rs, i) -> new SeqOwner(rs.getString("tbl"), rs.getString("col"), rs.getString("seq")));
    }

    @Test
    @DisplayName("从零库上没有任何序列落后于已占主键（显式主键种子必须同文件 setval）")
    void noSequenceLagsBehindSeededIds() {
        List<SeqOwner> owners = ownedSequences();
        assertTrue(owners.size() > 100, "序列宿主列抽取异常，只拿到 " + owners.size() + " 个（判据会假绿）");

        List<String> lagging = new ArrayList<>();
        for (SeqOwner o : owners) {
            Long maxValue = jdbc.queryForObject(
                    "SELECT coalesce(max(" + quote(o.column) + ")::bigint, 0) FROM " + o.table(), Long.class);
            String state = jdbc.queryForObject("SELECT last_value::text || '|' || is_called::text FROM " + o.sequence(), String.class);
            long lastValue = Long.parseLong(state.split("\\|")[0]);
            boolean called = Boolean.parseBoolean(state.split("\\|")[1]);
            // is_called=false 时下一个 nextval 就返回 last_value 本身
            long nextIssued = called ? lastValue + 1 : lastValue;
            if (nextIssued <= maxValue) {
                lagging.add(o.table() + "." + o.column() + " 下一个发号=" + nextIssued + " 但 MAX=" + maxValue);
            }
        }
        assertTrue(lagging.isEmpty(), () -> lagging.size() + " 个序列会撞已存在主键（新环境建号即 500，或被 upsert 覆盖）：\n"
                + String.join("\n", lagging));
    }

    @Test
    @DisplayName("界面连建 3 个角色（role_id 由库默认发号）不撞 ops_role_pkey")
    void threeRoleInsertsNeverCollide() {
        Long seedMax = jdbc.queryForObject("SELECT coalesce(max(role_id),0) FROM ops_role", Long.class);
        // V4 的 serial 只推到 8，V140/V162 显式插到 11：漂移未校准前第 2 次插入必撞号（UAT 实测 500）
        for (int i = 1; i <= 3; i++) {
            OpsRole role = new OpsRole();
            role.setRoleKey("SEQ_IT_ROLE_" + i);
            role.setRoleName("序列漂移回归角色" + i);
            role.setStatus("ACTIVE");
            role.setRemark("SeedSequenceDriftSqlIT");
            assertEquals(1, roleMapper.insert(role), "第 " + i + " 次建角色未落库");
            assertTrue(role.getRoleId() > seedMax,
                    "第 " + i + " 次建角色拿到 roleId=" + role.getRoleId() + "，不大于种子 MAX=" + seedMax + "（发的是已被占用的号）");
        }
    }

    private static String quote(String identifier) {
        assertTrue(identifier.matches("[a-z_][a-z0-9_]*"), "目录里出现非法标识符：" + identifier);
        return "\"" + identifier + "\"";
    }
}
