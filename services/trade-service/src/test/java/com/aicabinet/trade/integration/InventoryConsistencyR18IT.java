package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.DeviceSkuInventory;
import com.aicabinet.trade.domain.DeviceSkuInventoryId;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R18-2/4/5（四端审计 2026-11-12）的<b>真库并发实证</b>。
 *
 * <p>P-1 把汇总/批次的扣减改成了单语句原子 UPDATE，但审计发现三处没跟上（铁律 75：
 * 修的是「这一类」不是「这一处」）：批次回补/跨批核销仍是读改写（R18-2）、
 * 建档竞态 catch 后同事务继续发 SQL 会撞 PG aborted（R18-4）、算术 UPDATE 不递增
 * version 使实体 CAS 失明（R18-5）。本 IT 钉五件事：</p>
 *
 * <ol>
 *   <li>R18-2：批次「FEFO 扣 5 与下架扣 3」两路原子并发，终量必须为 2——
 *       任何一路若退回读改写（绝对覆盖），另一路已提交的扣减会被旧快照覆盖（丢扣）；</li>
 *   <li>R18-2 负向控制：绝对覆盖写（旧形态）与原子扣并发，覆盖后提交确实会丢扣
 *       ——证明竞态真实存在、原子化不是过度设计；</li>
 *   <li>R18-4：汇总行缺失时两连接并发 upsert 回补，双方都成功且终量=两笔之和
 *       （旧形态 catch DuplicateKeyException 后第二条 UPDATE 必撞 PG aborted 25P02）；</li>
 *   <li>R18-5：原子扣提交后用旧 version 走实体 save，CAS 必须失败（影响 0 行）——
 *       失明状态下会把刚扣掉的量按旧快照写回；</li>
 *   <li>R18-5 正向：用当前 version 的 save 正常收敛且 version 递增（汇总重试链依赖）。</li>
 * </ol>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class InventoryConsistencyR18IT {

    private static final String DEVICE_ID = "IT-INV-R18";
    private static final String SKU_ID = "IT-SKU-R18";

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
                + "VALUES (?, 'R18 一致性 IT 柜', 'AI_CABINET_V1', 'OFFLINE') "
                + "ON CONFLICT (device_id) DO NOTHING", DEVICE_ID);
        jdbc.update("INSERT INTO sku_catalog (sku_id, sku_name, price_cents, vision_enabled) "
                + "VALUES (?, 'R18 一致性 IT 商品', 350, FALSE) "
                + "ON CONFLICT (sku_id) DO NOTHING", SKU_ID);
    }

    private String seedLot(int quantity) {
        String lotId = "IT-LOT-R18-" + System.nanoTime();
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

    private Integer aggregateQty() {
        return jdbc.queryForObject(
                "SELECT quantity FROM device_sku_inventory WHERE device_id = ? AND sku_id = ?",
                Integer.class, DEVICE_ID, SKU_ID);
    }

    @Test
    @DisplayName("R18-2 批次并发：FEFO 扣 5 与下架扣 3 同时提交，终量必须为 2")
    void lotAtomicDeduct_twoPathsConcurrent_noLostDeduct() throws Exception {
        String lotId = seedLot(10);

        List<Integer> results = runConcurrently(List.of(
                () -> lotMapper.deductQuantityAtomic(lotId, 5),
                () -> lotMapper.deductQuantityAtomic(lotId, 3)
        ));

        assertTrue(results.stream().allMatch(r -> r == 1),
                "10 扣 5+3 两路都必须成功，实际：" + results);
        Integer qty = jdbc.queryForObject(
                "SELECT quantity FROM device_sku_lot WHERE lot_id = ?", Integer.class, lotId);
        assertEquals(2, qty, "终量必须为 2（少扣=账面虚高可再卖，多扣=负库存）");
    }

    @Test
    @DisplayName("R18-2 负向控制：绝对覆盖写与原子扣并发会丢扣——竞态真实存在")
    void legacyAbsoluteOverwrite_stillLosesConcurrentDeduct() throws Exception {
        String lotId = seedLot(10);

        // 线程 A 原子扣 5（提交）；随后用旧「读→算→写」形态基于 qty=10 的快照写绝对值 7
        //（= 10 - 3，模拟下架方先读后写）。绝对覆盖会抹掉原子扣已提交的 -5。
        runTwoStages(
                () -> lotMapper.deductQuantityAtomic(lotId, 5),
                () -> jdbc.update("UPDATE device_sku_lot SET quantity = 7 WHERE lot_id = ?", lotId)
        );

        Integer overwritten = jdbc.queryForObject(
                "SELECT quantity FROM device_sku_lot WHERE lot_id = ?", Integer.class, lotId);
        assertEquals(7, overwritten,
                "绝对覆盖确实会覆盖原子结果——这就是批次账必须原子化的实证（旧形态在此红）");
    }

    @Test
    @DisplayName("R18-4 upsert 并发建档：行缺失双连接回补，双方成功且终量=两笔之和")
    void upsertIncrease_concurrentCreate_bothSucceed() throws Exception {
        seedAggregate(0);
        jdbc.update("DELETE FROM device_sku_inventory WHERE device_id = ? AND sku_id = ?",
                DEVICE_ID, SKU_ID);

        List<Integer> results = runConcurrently(List.of(
                () -> inventoryMapper.upsertIncreaseQuantity(DEVICE_ID, SKU_ID, 3),
                () -> inventoryMapper.upsertIncreaseQuantity(DEVICE_ID, SKU_ID, 4)
        ));

        assertTrue(results.stream().allMatch(r -> r == 1),
                "并发建档回补双方都必须成功（旧形态必有一方撞 PG aborted 500），实际：" + results);
        assertEquals(7, aggregateQty(), "终量必须为两笔之和（增量丢失=退款回补凭空少货）");

        // upsert 建档默认值与原 Java 建档口径一致（capacity=20 / low_threshold=2 / version 起点 0）
        var row = jdbc.queryForMap(
                "SELECT capacity, low_threshold, version FROM device_sku_inventory "
                        + "WHERE device_id = ? AND sku_id = ?", DEVICE_ID, SKU_ID);
        assertEquals(20, ((Number) row.get("capacity")).intValue(), "capacity 默认须与原建档口径一致");
        assertEquals(2, ((Number) row.get("low_threshold")).intValue(), "low_threshold 默认须一致");
        // 并发双方都落账 ⇒ 恰一方 INSERT（version=0）另一方走 DO UPDATE（version 递增 1 次）
        // ——若双方都 INSERT 或都 UPDATE，quantity=7 不可能成立，version=1 是「INSERT+UPDATE 各一」的指纹
        assertEquals(1L, ((Number) row.get("version")).longValue(),
                "并发双方都成功时 version=1（一方 INSERT + 一方 DO UPDATE 各一次递增）");
    }

    @Test
    @DisplayName("R18-5 原子扣递增 version：旧 version 的实体 save CAS 必须失败（不再失明）")
    void atomicDeduct_bumpsVersion_staleCasFails() {
        seedAggregate(10);
        Long v0 = jdbc.queryForObject(
                "SELECT version FROM device_sku_inventory WHERE device_id = ? AND sku_id = ?",
                Long.class, DEVICE_ID, SKU_ID);

        assertEquals(1, inventoryMapper.deductQuantityAtomic(DEVICE_ID, SKU_ID, 2));
        assertEquals(8, aggregateQty(), "原子扣应生效");

        // 旧快照（version=v0）走实体 save——R18-5 修复前 CAS 会「成功」并把 qty 写回旧值
        DeviceSkuInventory stale = new DeviceSkuInventory();
        stale.setId(new DeviceSkuInventoryId(DEVICE_ID, SKU_ID));
        stale.setQuantity(10);
        stale.setVersion(v0);
        assertThrows(ResponseStatusException.class, () -> inventoryMapper.save(stale),
                "旧 version 的 CAS 必须失败——失明=把刚扣掉的量按旧快照写回");

        Long vAfter = jdbc.queryForObject(
                "SELECT version FROM device_sku_inventory WHERE device_id = ? AND sku_id = ?",
                Long.class, DEVICE_ID, SKU_ID);
        assertEquals(v0 + 1, vAfter, "原子扣必须递增 version");
        assertEquals(8, aggregateQty(), "失败的 CAS 不得覆盖任何值");

        // 正向：当前 version 的 save 正常收敛（syncAggregateInventory 重试链依赖此路径）
        DeviceSkuInventory fresh = new DeviceSkuInventory();
        fresh.setId(new DeviceSkuInventoryId(DEVICE_ID, SKU_ID));
        fresh.setQuantity(8);
        fresh.setVersion(vAfter);
        inventoryMapper.save(fresh);
        Long vFinal = jdbc.queryForObject(
                "SELECT version FROM device_sku_inventory WHERE device_id = ? AND sku_id = ?",
                Long.class, DEVICE_ID, SKU_ID);
        assertEquals(vAfter + 1, vFinal, "当前 version 的 save 应成功并递增 version");
    }

    // ==== 并发助手（与 InventoryDeductAtomicityIT 同款） ====

    private List<Integer> runConcurrently(List<Callable<Integer>> calls) throws Exception {
        int threads = calls.size();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> call : calls) {
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
            List<Integer> outcomes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            assertTrue(outcomes.stream().noneMatch(r -> r == -999), "并发线程出现异常：" + outcomes);
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 先 A 后 B 两阶段提交（用于负向控制：证明绝对覆盖会覆盖已提交的原子结果）。 */
    private Integer runTwoStages(Callable<Integer> first, Callable<Integer> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<Integer> f1 = pool.submit(() -> {
                try {
                    return first.call();
                } catch (Throwable t) {
                    return -999;
                }
            });
            assertEquals(Integer.valueOf(1), f1.get(30, TimeUnit.SECONDS), "第一阶段须成功");
            pool.submit(() -> {
                try {
                    second.call();
                    return null;
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }).get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        return null;
    }
}
