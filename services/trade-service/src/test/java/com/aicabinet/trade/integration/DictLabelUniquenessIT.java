package com.aicabinet.trade.integration;

import com.aicabinet.trade.service.RepairTicketService;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 * 门禁：字典里**同一 dict_type 下不得有两个 ACTIVE 项同名**（用户 2026-10-31 发现下拉里「已回复」出现两次）。
 *
 * <p>为什么用真库 IT 而不是静态扫 SQL：迁移里字典插入有 ≥4 种写法
 * （`INSERT … VALUES`、`INSERT … SELECT … FROM (VALUES …)`、带 EXISTS 守卫的单行 SELECT 等），
 * 正则解析漏报率高；而「全量迁移跑完后库里到底有没有重名」才是唯一真值。</p>
 *
 * <p>棘轮：{@link #KNOWN_DUPLICATES} 是**已知历史例外**（值为 `type → label`）。
 * 实际重名集合必须与之**完全相等**：
 * <ul>
 *   <li>新增重名 ⇒ 红（禁止再加）；</li>
 *   <li>已清理的条目还留在清单里 ⇒ 红（提示同步下调，防清单腐烂）。</li>
 * </ul>
 * 清理某个历史重名后，请同步删除对应条目并写清迁移号。</p>
 * <p>第二个用例（MA-146/V438）把「字典值 = 状态机可写状态」钉住：字典里多出一个任何写入点都产不出的值，
 * 就是后台筛选里的幽灵选项；少一个则说明状态机新增了状态却没人给它标签。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class DictLabelUniquenessIT {

    /**
     * 已知历史重名（type → label）。新增必须同时给出理由与计划，否则本门禁会拦。
     *
     * <p>清单来自 2026-10-31 首次实跑（用户发现下拉里「已回复」出现两次后要求全量排查）；
     * 清理某一组时请**同时删掉这里的条目**（棘轮会在清单多余时红）。</p>
     */
    static final Map<String, List<String>> KNOWN_DUPLICATES = new LinkedHashMap<>();

    static {
        // 2026-10-31 MA-018 已按用户拍板「彻底清除」：
        //   V418 迁数据 + 删别名/死值字典行，SysDictBootstrap 同步去种子，
        //   enable_status 的 DISABLED 拆到 bin_status。
        // ⇒ 干净迁移库里不应再有任何同名标签；若本测试变红，说明新代码又引入了重名。
    }


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

    @Test
    @DisplayName("同一 dict_type 下不得有两个 ACTIVE 项同名（棘轮：只允许 KNOWN_DUPLICATES 列出的历史例外）")
    void activeDictLabels_mustBeUniquePerType() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT dict_type, dict_label, COUNT(*) AS c, "
                        + "STRING_AGG(dict_value, ',' ORDER BY dict_value) AS vals "
                        + "FROM sys_dict_data WHERE status = 'ACTIVE' "
                        + "GROUP BY dict_type, dict_label HAVING COUNT(*) > 1 "
                        + "ORDER BY dict_type, dict_label");

        Map<String, String> actual = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            actual.put(String.valueOf(r.get("dict_type")) + "|" + String.valueOf(r.get("dict_label")),
                    String.valueOf(r.get("vals")));
        }
        Map<String, String> expected = new TreeMap<>();
        KNOWN_DUPLICATES.forEach((type, labels) -> labels.forEach(l -> expected.put(type + "|" + l, "")));

        assertTrue(expected.keySet().equals(actual.keySet()),
                "字典重名集合与白名单不一致（新增重名或清单未更新）。\n"
                        + "  实际：" + actual + "\n"
                        + "  白名单：" + expected + "\n"
                        + "  处理：清理历史重名后删除白名单条目；新代码引入的重名必须改掉而不是加白名单。");
    }

    @Test
    @DisplayName("repair_ticket_status 字典值集合 = 工单状态机可写状态集合（幽灵选项 / 漏标签双向钉测）")
    void repairTicketDictValues_mustMatchStateMachineWritableStatuses() throws Exception {
        Field field = RepairTicketService.class.getDeclaredField("STATUSES");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> writable = (Set<String>) field.get(null);

        Set<String> dictValues = new HashSet<>(jdbc.queryForList(
                "SELECT dict_value FROM sys_dict_data "
                        + "WHERE dict_type = 'repair_ticket_status' AND status = 'ACTIVE'",
                String.class));

        assertEquals(new java.util.TreeSet<>(writable), new java.util.TreeSet<>(dictValues),
                "维修工单状态字典与 RepairTicketService.STATUSES 不一致："
                        + "库里多出的值 = 后台筛选里点了也永远查不到单的幽灵选项（MA-146/V438）；"
                        + "库里缺少的值 = 新状态没有标签，界面会显示裸码。");
    }
}
