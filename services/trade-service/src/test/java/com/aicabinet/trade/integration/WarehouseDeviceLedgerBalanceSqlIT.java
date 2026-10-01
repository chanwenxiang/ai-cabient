package com.aicabinet.trade.integration;

import com.aicabinet.trade.service.DataConsistencyService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真跑 SQL：仓账/柜账两条「期初 + Σ流水 = 余额」公式巡检（WAREHOUSE_LEDGER / DEVICE_LEDGER）。
 *
 * <p>背景：借鉴旧弹簧柜月台账公式（docs/SYSTEM_COMPARISON_EASYGO_VS_AICABINET_2026-10-01.md §5），
 * 给仓储/柜机双账本加余额公式安全网。单测 {@code DataConsistencyServiceTest} 只 mock JdbcTemplate，
 * 碰不到 JOIN 键 / COALESCE(batch) 语义，本 IT 用真库钉住：</p>
 * <ul>
 *   <li>账实一致（期初流水 + 发生流水 = 余额）→ 不记 FAIL</li>
 *   <li>注入漂移（余额 ≠ 流水合计）→ 记 FAIL，key 为 wh|sku|batch / dev|sku|batch</li>
 *   <li>批次拆多货道 lot（同批次两行）→ 按批次聚合后公式仍成立</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class WarehouseDeviceLedgerBalanceSqlIT {

    private static final String WH = "WH-LED-IT";
    private static final String SKU = "SKU-LED-IT";
    private static final String DEV = "DEV-LED-IT";

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
    private DataConsistencyService consistencyService;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM data_consistency_record WHERE check_type IN ('WAREHOUSE_LEDGER','DEVICE_LEDGER')");
        jdbc.update("DELETE FROM inventory_movement WHERE device_id = ?", DEV);
        jdbc.update("DELETE FROM device_sku_lot WHERE device_id = ?", DEV);
        jdbc.update("DELETE FROM warehouse_movement WHERE warehouse_id = ?", WH);
        jdbc.update("DELETE FROM warehouse_inventory WHERE warehouse_id = ?", WH);
        jdbc.update("DELETE FROM warehouse WHERE warehouse_id = ?", WH);
        jdbc.update("DELETE FROM device_info WHERE device_id = ?", DEV);
        jdbc.update("DELETE FROM sku_catalog WHERE sku_id IN (?, 'SKU-LED-IT-2')", SKU);
    }

    private void seedParents() {
        jdbc.update("INSERT INTO warehouse (warehouse_id, warehouse_name) VALUES (?, 'ledger-it') "
                + "ON CONFLICT (warehouse_id) DO NOTHING", WH);
        jdbc.update("INSERT INTO sku_catalog (sku_id, sku_name, price_cents) VALUES (?, 'ledger-it sku', 100) "
                + "ON CONFLICT (sku_id) DO NOTHING", SKU);
        jdbc.update("INSERT INTO sku_catalog (sku_id, sku_name, price_cents) VALUES ('SKU-LED-IT-2', 'ledger-it sku2', 100) "
                + "ON CONFLICT (sku_id) DO NOTHING");
        jdbc.update("INSERT INTO device_info (device_id, device_name, device_type, online_status) "
                + "VALUES (?, 'ledger-it dev', 'AI_CABINET_V1', 'OFFLINE') ON CONFLICT (device_id) DO NOTHING", DEV);
    }

    private void insertWarehouseInventory(String skuId, String batchNo, int qty) {
        jdbc.update("INSERT INTO warehouse_inventory (warehouse_id, sku_id, batch_no, expiry_date, quantity) "
                        + "VALUES (?, ?, ?, ?, ?)",
                WH, skuId, batchNo, LocalDate.now().plusMonths(6), qty);
    }

    private void insertWarehouseMovement(String skuId, String batchNo, String type, int delta) {
        jdbc.update("INSERT INTO warehouse_movement (warehouse_id, sku_id, batch_no, movement_type, delta_qty, ref_type, ref_id) "
                        + "VALUES (?, ?, ?, ?, ?, 'LED-IT', '1')", WH, skuId, batchNo, type, delta);
    }

    private void insertLot(String lotId, String skuId, String batchNo, int qty) {
        jdbc.update("INSERT INTO device_sku_lot (lot_id, device_id, sku_id, batch_no, expiry_date, quantity, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'ON_SALE')",
                lotId, DEV, skuId, batchNo, LocalDate.now().plusMonths(6), qty);
    }

    private void insertDeviceMovement(String skuId, String batchNo, String type, int delta) {
        jdbc.update("INSERT INTO inventory_movement (device_id, sku_id, batch_no, movement_type, delta_qty, ref_type, ref_id) "
                        + "VALUES (?, ?, ?, ?, ?, 'LED-IT', '1')", DEV, skuId, batchNo, type, delta);
    }

    private int failCount(String checkType, String key) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM data_consistency_record WHERE check_type = ? AND check_key = ? AND status = 'FAIL'",
                Integer.class, checkType, key);
        return count != null ? count : 0;
    }

    @Test
    @DisplayName("仓账：期初+Σ流水=余额 不记 FAIL；注入漂移记 FAIL")
    void warehouseLedger_alignedGreen_driftRed() {
        seedParents();
        insertWarehouseInventory(SKU, "B-OK", 5);
        insertWarehouseMovement(SKU, "B-OK", "MANUAL_INBOUND", 5);
        insertWarehouseInventory(SKU, "B-DRIFT", 9);
        insertWarehouseMovement(SKU, "B-DRIFT", "MANUAL_INBOUND", 4);

        consistencyService.runConsistencyCheck();

        assertEquals(0, failCount("WAREHOUSE_LEDGER", WH + "|" + SKU + "|B-OK"),
                "账实一致组不得报 FAIL（若报红说明公式 JOIN 键错）");
        assertEquals(1, failCount("WAREHOUSE_LEDGER", WH + "|" + SKU + "|B-DRIFT"),
                "余额9≠流水4 必须报 FAIL（注入漂移证明判据会红）");
    }

    @Test
    @DisplayName("柜账：批次拆两货道 lot 按批次聚合公式成立；直接改库漂移记 FAIL")
    void deviceLedger_multiLotAggregated_driftRed() {
        seedParents();
        insertLot("LOT-LED-A", SKU, "B-OK", 5);
        insertLot("LOT-LED-B", SKU, "B-OK", 3);
        insertDeviceMovement(SKU, "B-OK", "RESTOCK", 8);
        insertLot("LOT-LED-C", "SKU-LED-IT-2", "B-DRIFT", 5);
        insertDeviceMovement("SKU-LED-IT-2", "B-DRIFT", "RESTOCK", 8);
        insertDeviceMovement("SKU-LED-IT-2", "B-DRIFT", "SALE", -2);

        consistencyService.runConsistencyCheck();

        assertEquals(0, failCount("DEVICE_LEDGER", DEV + "|" + SKU + "|B-OK"),
                "两 lot 行 5+3 与流水 +8 按批次聚合必须一致");
        assertEquals(1, failCount("DEVICE_LEDGER", DEV + "|SKU-LED-IT-2|B-DRIFT"),
                "余额5≠流水+6 必须报 FAIL（注入漂移证明判据会红）");
    }
}
