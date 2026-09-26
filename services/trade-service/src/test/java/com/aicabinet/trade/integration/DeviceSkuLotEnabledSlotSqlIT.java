package com.aicabinet.trade.integration;

import com.aicabinet.trade.mapper.DeviceSkuLotMapper;
import org.junit.jupiter.api.AfterEach;
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

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真跑 SQL：消费者可售量必须排除「明确绑到已禁用货道」的批次。
 *
 * <p>背景：运营关掉货道启用后，小程序仍显示该货道商品。根因是旧查询
 * {@code selectSumSellableBySku} 不 JOIN {@code device_slot}。修复落在
 * {@code DeviceSkuLotMapper.xml#selectSumSellableBySkuOnEnabledSlots}
 *（LEFT JOIN + {@code COALESCE(s.enabled, true)}）。</p>
 *
 * <p>{@code DeviceCatalogServiceTest} 只 Mockito 钉住「服务层调用新方法」，
 * 碰不到 JOIN 键 / COALESCE 语义。本 IT 用三种 fixture 钉 SQL：</p>
 * <ul>
 *   <li>启用货道批次 → 计入</li>
 *   <li>禁用货道批次 → 不计入（JOIN 命中 + enabled=false）</li>
 *   <li>未绑货道批次 → 计入（LEFT JOIN 无匹配，COALESCE 兜底 true）</li>
 * </ul>
 * <p>注：V219 起 {@code (device_id, slot_id)} FK 到 {@code device_slot}，新写入
 * 的 slot_id 必须与 slot_code 完全一致；SQL 里的 {@code UPPER()} 是历史数据兜底，
 * 本 IT 不伪造大小写不一致行（FK 拦得住）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class DeviceSkuLotEnabledSlotSqlIT {

    private static final String DEVICE_ID = "CAB-SLOT-SQL-IT";
    private static final String SKU_ID = "SKU-SLOT-SQL-IT";
    private static final String LOT_ENABLED = "LOT-SLOT-EN";
    private static final String LOT_DISABLED = "LOT-SLOT-DIS";
    private static final String LOT_UNBOUND = "LOT-SLOT-UNB";

    @Container
    @SuppressWarnings("resource") // lifecycle owned by Testcontainers JUnit extension
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource") // lifecycle owned by Testcontainers JUnit extension
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
    private DeviceSkuLotMapper lotMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM device_sku_lot WHERE device_id = ?", DEVICE_ID);
        jdbc.update("DELETE FROM device_slot WHERE device_id = ?", DEVICE_ID);
        jdbc.update("DELETE FROM device_info WHERE device_id = ?", DEVICE_ID);
        jdbc.update("DELETE FROM sku_catalog WHERE sku_id = ?", SKU_ID);
    }

    private void seedParents() {
        jdbc.update(
                "INSERT INTO device_info (device_id, device_name, device_type, online_status)"
                        + " VALUES (?, 'slot-sql-it', 'AI_CABINET_V1', 'OFFLINE')"
                        + " ON CONFLICT (device_id) DO NOTHING",
                DEVICE_ID);
        jdbc.update(
                "INSERT INTO sku_catalog (sku_id, sku_name, price_cents)"
                        + " VALUES (?, 'slot-sql-it sku', 100)"
                        + " ON CONFLICT (sku_id) DO NOTHING",
                SKU_ID);
    }

    private void seedSlots() {
        // slot_code 大写写入（与生产 toUpperCase 一致）
        jdbc.update(
                "INSERT INTO device_slot (device_id, slot_code, row_no, col_no, enabled)"
                        + " VALUES (?, 'A1', 1, 1, TRUE)"
                        + " ON CONFLICT (device_id, slot_code) DO UPDATE SET enabled = EXCLUDED.enabled",
                DEVICE_ID);
        jdbc.update(
                "INSERT INTO device_slot (device_id, slot_code, row_no, col_no, enabled)"
                        + " VALUES (?, 'B1', 2, 1, FALSE)"
                        + " ON CONFLICT (device_id, slot_code) DO UPDATE SET enabled = EXCLUDED.enabled",
                DEVICE_ID);
    }

    private void insertLot(String lotId, String slotId, int qty) {
        LocalDate expiry = LocalDate.now().plusMonths(6);
        jdbc.update(
                "INSERT INTO device_sku_lot"
                        + " (lot_id, device_id, sku_id, batch_no, expiry_date, quantity, slot_id, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'ON_SALE')",
                lotId, DEVICE_ID, SKU_ID, "BATCH-" + lotId, expiry, qty, slotId);
    }

    private static Map<String, Integer> qtyBySku(List<Object[]> rows) {
        Map<String, Integer> map = new HashMap<>();
        for (Object[] row : rows) {
            String sku = String.valueOf(row[0]);
            int qty = ((Number) row[1]).intValue();
            map.put(sku, qty);
        }
        return map;
    }

    @Test
    @DisplayName("消费者口径：禁用货道不计入；启用货道与未绑货道仍计入")
    void consumerSum_excludesDisabledSlot_includesEnabledAndUnbound() {
        seedParents();
        seedSlots();
        insertLot(LOT_ENABLED, "A1", 10);
        insertLot(LOT_DISABLED, "B1", 7);
        insertLot(LOT_UNBOUND, null, 5);

        Map<String, Integer> consumer = qtyBySku(lotMapper.sumSellableBySkuOnEnabledSlots(DEVICE_ID));
        Map<String, Integer> ops = qtyBySku(lotMapper.sumSellableBySku(DEVICE_ID));

        // 10(A1 启用) + 5(未绑) = 15；禁用 B1 的 7 不得计入
        assertEquals(15, consumer.getOrDefault(SKU_ID, 0),
                "消费者口径应排除禁用货道；若仍为 22 说明 JOIN/enabled 过滤失效");
        assertEquals(22, ops.getOrDefault(SKU_ID, 0),
                "运营口径应含禁用货道账面（10+7+5）");
        assertTrue(consumer.getOrDefault(SKU_ID, 0) < ops.getOrDefault(SKU_ID, 0),
                "两口径必须分离：消费者 < 运营（禁用货道差额）");
    }

    @Test
    @DisplayName("若禁用货道 JOIN 不上，COALESCE 会把禁用批次放行——本用例钉住 JOIN 键能命中")
    void disabledSlot_mustJoin_otherwiseCoalesceWouldLeakSellable() {
        seedParents();
        seedSlots();
        insertLot(LOT_DISABLED, "B1", 7);

        Map<String, Integer> consumer = qtyBySku(lotMapper.sumSellableBySkuOnEnabledSlots(DEVICE_ID));
        assertEquals(0, consumer.getOrDefault(SKU_ID, 0),
                "仅禁用货道批次时消费者可售量必须为 0；若为 7 则 JOIN 键未命中、COALESCE 误放行");
    }
}
