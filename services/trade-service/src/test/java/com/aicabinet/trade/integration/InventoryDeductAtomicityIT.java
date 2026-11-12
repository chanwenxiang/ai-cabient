package com.aicabinet.trade.integration;

import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.DeviceSkuLotMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集群就绪 P-1 的<b>真库并发实证</b>（TRI_CLIENT_BACKLOG §六 P-1，2026-11-12）。
 *
 * <p>库存两条腿（批次 {@code device_sku_lot} / 汇总 {@code device_sku_inventory}）的扣减
 * 原来是「读→算→写」：批次表无 version 无 CAS，汇总表的实体 CAS 防丢更新但防不住
 * 「先读后判不足」的窗口，Redis 设备锁是唯一防线——多副本 + 租约过期的组合下无第二道闸。
 * 本 IT 用真 PostgreSQL 双线程同时打同一行，钉四件事：</p>
 *
 * <ol>
 *   <li>单语句原子扣减（{@code deductQuantityAtomic}）并发双打同批次：恰 1 方成功，
 *       终量正确——{@code quantity >= take} 谓词与写入同语句，无检查-写入间隙；</li>
 *   <li>量守卫不是「恰好抢到」的运气：qty=5 双方各扣 3，恰 1 成 1 败、终量 2；</li>
 *   <li>V457 CHECK 兜底：无守卫的裸 {@code SET quantity = quantity - ?} 超扣在 DB 层
 *       当场抛约束冲突（宁拒勿负）；</li>
 *   <li>CHECK 语义对直插负值同样成立（两张表）。</li>
 * </ol>
 *
 * <p>测试直打 mapper（不经 {@code runWithDeviceLotLock}）：要钉的是 <b>SQL 形态本身</b>
 * 的原子性契约——应用层 Redis 锁失效时 DB 是最后防线；锁在不在都不影响本 IT 的判定。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class InventoryDeductAtomicityIT {

    private static final String DEVICE_ID = "IT-INV-001";
    private static final String SKU_ID = "IT-SKU-001";

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DeviceSkuLotMapper lotMapper;
    @Autowired private DeviceSkuInventoryMapper inventoryMapper;

    @BeforeEach
    void seedSubjects() {
        jdbc.update("INSERT INTO device_info (device_id, device_name, device_type, online_status) "
                + "VALUES (?, '原子扣减 IT 柜', 'AI_CABINET_V1', 'OFFLINE') "
                + "ON CONFLICT (device_id) DO NOTHING", DEVICE_ID);
        jdbc.update("INSERT INTO sku_catalog (sku_id, sku_name, price_cents, vision_enabled) "
                + "VALUES (?, '原子扣减 IT 商品', 350, FALSE) "
                + "ON CONFLICT (sku_id) DO NOTHING", SKU_ID);
    }

    private String seedLot(int quantity) {
        String lotId = "IT-LOT-" + System.nanoTime();
        jdbc.update("INSERT INTO device_sku_lot (lot_id, device_id, sku_id, batch_no, expiry_date, "
                + "quantity, status) VALUES (?, ?, ?, ?, ?, ?, 'ON_SALE')",
                lotId, DEVICE_ID, SKU_ID, "B-" + System.nanoTime(),
                LocalDate.now().plusYears(1), quantity);
        return lotId;
    }

    private void seedAggregate(int quantity) {
        jdbc.update("DELETE FROM device_sku_inventory WHERE device_id = ? AND sku_id = ?",
                DEVICE_ID, SKU_ID);
        jdbc.update("INSERT INTO device_sku_inventory (device_id, sku_id, quantity, capacity, low_threshold) "
                + "VALUES (?, ?, ?, 20, 2)", DEVICE_ID, SKU_ID, quantity);
    }

    @Test
    @DisplayName("批次原子扣减：qty=1 双线程各扣 1，恰 1 成 1 败、终量 0 且置 DEPLETED")
    void concurrentLotDeduct_exactlyOnce() throws Exception {
        String lotId = seedLot(1);

        List<Integer> results = runTwoThreads(() -> lotMapper.deductQuantityAtomic(lotId, 1));

        long wins = results.stream().filter(r -> r == 1).count();
        assertEquals(1, wins, "并发双扣应恰 1 方成功，实际：" + results);
        Integer qty = jdbc.queryForObject(
                "SELECT quantity FROM device_sku_lot WHERE lot_id = ?", Integer.class, lotId);
        assertEquals(0, qty, "终量必须为 0（多扣=负库存，少扣=超卖）");
        String status = jdbc.queryForObject(
                "SELECT status FROM device_sku_lot WHERE lot_id = ?", String.class, lotId);
        assertEquals("DEPLETED", status, "归零必须自动置 DEPLETED");
    }

    @Test
    @DisplayName("批次量守卫：qty=5 双方各扣 3，恰 1 成 1 败、终量 2")
    void concurrentLotDeduct_quantityGuard() throws Exception {
        String lotId = seedLot(5);

        List<Integer> results = runTwoThreads(() -> lotMapper.deductQuantityAtomic(lotId, 3));

        long wins = results.stream().filter(r -> r == 1).count();
        assertEquals(1, wins, "qty=5 扣 3×2 只允许 1 方成功，实际：" + results);
        Integer qty = jdbc.queryForObject(
                "SELECT quantity FROM device_sku_lot WHERE lot_id = ?", Integer.class, lotId);
        assertEquals(2, qty, "终量必须为 2");
    }

    @Test
    @DisplayName("汇总台账原子扣减：qty=1 双线程各扣 1，恰 1 成 1 败、终量 0")
    void concurrentAggregateDeduct_exactlyOnce() throws Exception {
        seedAggregate(1);

        List<Integer> results = runTwoThreads(() ->
                inventoryMapper.deductQuantityAtomic(DEVICE_ID, SKU_ID, 1));

        long wins = results.stream().filter(r -> r == 1).count();
        assertEquals(1, wins, "并发双扣应恰 1 方成功，实际：" + results);
        Integer qty = jdbc.queryForObject(
                "SELECT quantity FROM device_sku_inventory WHERE device_id = ? AND sku_id = ?",
                Integer.class, DEVICE_ID, SKU_ID);
        assertEquals(0, qty, "终量必须为 0");
    }

    @Test
    @DisplayName("V457 CHECK 兜底：无守卫的裸超扣 SQL 在 DB 层被拒（宁拒勿负）")
    void checkConstraint_rejectsUnguardedOverDeduct() {
        seedAggregate(1);
        // 这就是旧「读→算→写」形态在 DB 里的落地方式——修复后应用层不再发这种 SQL，
        // 但任何漏改的写法只要敢超扣，CHECK 就当场把它打回来
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("UPDATE device_sku_inventory SET quantity = quantity - 5 "
                        + "WHERE device_id = ? AND sku_id = ?", DEVICE_ID, SKU_ID));
        Integer qty = jdbc.queryForObject(
                "SELECT quantity FROM device_sku_inventory WHERE device_id = ? AND sku_id = ?",
                Integer.class, DEVICE_ID, SKU_ID);
        assertEquals(1, qty, "被拒的超扣不得留下任何变化");
    }

    @Test
    @DisplayName("V457 CHECK 兜底：两张表直插负值均被拒")
    void checkConstraint_rejectsNegativeInsert() {
        // 负插必须引用「FK 已满足」的主体：异常只允许来自 CHECK，不能让 FK 替它变红
        jdbc.update("DELETE FROM device_sku_inventory WHERE device_id = ? AND sku_id = ?",
                DEVICE_ID, SKU_ID);
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO device_sku_inventory (device_id, sku_id, quantity, capacity, low_threshold) "
                        + "VALUES (?, ?, -1, 20, 2)", DEVICE_ID, SKU_ID));
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO device_sku_lot (lot_id, device_id, sku_id, batch_no, expiry_date, "
                        + "quantity, status) VALUES ('IT-LOT-NEG', ?, ?, 'B-NEG', ?, -1, 'ON_SALE')",
                        DEVICE_ID, SKU_ID, LocalDate.now().plusYears(1)));
    }

    @Test
    @DisplayName("汇总台账原子累加：并发回库不丢增量")
    void concurrentAggregateIncrease_noLostUpdate() throws Exception {
        seedAggregate(0);

        runTwoThreads(() -> inventoryMapper.increaseQuantityAtomic(DEVICE_ID, SKU_ID, 3));

        Integer qty = jdbc.queryForObject(
                "SELECT quantity FROM device_sku_inventory WHERE device_id = ? AND sku_id = ?",
                Integer.class, DEVICE_ID, SKU_ID);
        assertEquals(6, qty, "两路 +3 必须都落账（累加丢失=退款回库凭空少货）");
    }

    // ==== 并发助手（与 CloseClaimAndExpiryTaskRowLockSqlIT 同款） ====

    private List<Integer> runTwoThreads(Callable<Integer> call) throws Exception {
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Integer> outcomes = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    try {
                        return call.call();
                    } catch (Throwable t) {
                        return -999;
                    }
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            for (Future<Integer> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(outcomes.stream().noneMatch(r -> r == -999), "并发线程出现异常：" + outcomes);
        return outcomes;
    }
}
