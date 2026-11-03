package com.aicabinet.trade.integration;

import com.aicabinet.common.dto.CreateFromExpiryRequest;
import com.aicabinet.trade.domain.PullOffTask;
import com.aicabinet.trade.domain.WarehouseMonthlyCloseLine;
import com.aicabinet.trade.domain.WarehouseMonthlyCloseSheet;
import com.aicabinet.trade.mapper.PullOffTaskMapper;
import com.aicabinet.trade.mapper.WarehouseMonthlyCloseLineMapper;
import com.aicabinet.trade.mapper.WarehouseMonthlyCloseSheetMapper;
import com.aicabinet.trade.service.ReplenishmentService;
import com.aicabinet.trade.service.WarehouseMonthlyCloseSheetService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.http.HttpStatus;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-103 的<b>真库并发实证</b>（2026-11-03）。
 *
 * <p>两条腿原来都是「先读后写、无锁无版本」：{@code disposeLine} 用 selectById 判 PENDING 后
 * insert 索赔台账（{@code inventory_write_off}），{@code createTaskFromPullOff} 用 findById 判
 * OPEN 后建路线 + 任务 + 行。单测里 mapper 全是 mock，{@code findByIdForUpdate} 生成的
 * {@code FOR UPDATE} SQL <b>从未被任何数据库执行过</b>，所以「并发双击只挂一笔」这句话一直
 * 没有证据。本 IT 用真 PostgreSQL 两条腿各起两个线程同时打同一行，钉三件事：</p>
 *
 * <ol>
 *   <li>资金/派单动作<b>恰发生一次</b>（1 行 write_off / 1 条 from-expiry 任务）——
 *       这是缺陷本体，锁失效就会变 2；</li>
 *   <li>后到的那一方被服务层判据挡下（409「已处置」/409「已处理」），不是靠运气错开时间；</li>
 *   <li>{@code findByIdForUpdate} 在 PG 上真的下发行锁（第二个事务阻塞到第一个提交后才读到新状态）。</li>
 * </ol>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class CloseClaimAndExpiryTaskRowLockSqlIT {

    /** V2 种子已有该运营账号（verified）与设备 CAB-001 / SKU-WATER-001，直接复用避免再播主体。 */
    private static final long OPERATOR_ID = 10001L;
    private static final String DEVICE_ID = "CAB-001";
    private static final String SKU_ID = "SKU-WATER-001";

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
    @Autowired private WarehouseMonthlyCloseSheetService closeService;
    @Autowired private ReplenishmentService replenishmentService;
    @Autowired private WarehouseMonthlyCloseSheetMapper sheetMapper;
    @Autowired private WarehouseMonthlyCloseLineMapper lineMapper;
    @Autowired private PullOffTaskMapper pullOffTaskMapper;

    /**
     * 自己播主体，不依赖 V2 演示种子：`pull_off_task.device_id` 有 FK，
     * 而演示设备在后续清库迁移里已被删掉（本机实测 2026-11-03：CAB-001 不在库里）。
     */
    @BeforeEach
    void seedSubjects() {
        jdbc.update("INSERT INTO warehouse (warehouse_id, warehouse_name, status) "
                + "VALUES ('WH-IT-ROWLOCK', '行锁 IT 仓', 'ACTIVE') "
                + "ON CONFLICT (warehouse_id) DO NOTHING");
        jdbc.update("INSERT INTO device_info (device_id, device_name, device_type, online_status) "
                + "VALUES (?, '行锁 IT 柜', 'AI_CABINET_V1', 'OFFLINE') "
                + "ON CONFLICT (device_id) DO NOTHING", DEVICE_ID);
        jdbc.update("INSERT INTO sku_catalog (sku_id, sku_name, price_cents, vision_enabled) "
                + "VALUES (?, '行锁 IT 商品', 350, FALSE) "
                + "ON CONFLICT (sku_id) DO NOTHING", SKU_ID);
    }

    @Test
    @DisplayName("月结差异双击索赔：恰挂 1 笔 inventory_write_off，后到方 409")
    void concurrentClaim_writesOffExactlyOnce() throws Exception {
        long closeId = seedApprovedSheetWithGapLine();
        long lineId = jdbc.queryForObject(
                "SELECT line_id FROM warehouse_monthly_close_line WHERE close_id = ?",
                Long.class, closeId);
        String claimNo = "CLM-IT-" + System.nanoTime();

        List<Throwable> outcomes = runTwoThreads(() -> {
            closeService.disposeLine(OPERATOR_ID, closeId, lineId,
                    WarehouseMonthlyCloseLine.DISP_CLAIM, "SUPPLIER-A", claimNo);
            return null;
        });

        Integer writeOffs = jdbc.queryForObject(
                "SELECT count(*) FROM inventory_write_off WHERE claim_no = ?",
                Integer.class, claimNo);
        assertEquals(1, writeOffs, "同一盘亏行挂出多笔索赔台账（处置设计上不可逆 ⇒ 资金动作重复）");

        assertEquals(1, successes(outcomes), "成功次数应为 1，实际结果：" + describe(outcomes));
        assertEquals(1, conflicts(outcomes),
                "后到的一方没有被服务层判据挡下（说明 FOR UPDATE 没生效）：" + describe(outcomes));

        WarehouseMonthlyCloseLine line = lineMapper.selectById(lineId);
        assertEquals(WarehouseMonthlyCloseLine.DISP_CLAIM, line.getGapDisposition());
        assertTrue(line.getClaimWriteOffId() != null && line.getClaimWriteOffId() > 0,
                "CLAIM 行必须回写 claim_write_off_id，否则台账与行对不上号");
    }

    @Test
    @DisplayName("临期告警双击建任务：恰建 1 条 from-expiry 任务，后到方 409")
    void concurrentCreateFromExpiry_buildsOneTaskOnly() throws Exception {
        long pullOffTaskId = seedOpenPullOffTask();

        List<Throwable> outcomes = runTwoThreads(() -> {
            replenishmentService.createTaskFromPullOff(OPERATOR_ID, pullOffTaskId,
                    new CreateFromExpiryRequest("PULL_OFF", null));
            return null;
        });

        Integer tasks = jdbc.queryForObject(
                "SELECT count(*) FROM replenishment_task WHERE notes LIKE ?",
                Integer.class, "from-expiry:" + pullOffTaskId + "%");
        assertEquals(1, tasks, "同一条临期告警双建补货任务（重复出勤、重复扣货）");

        assertEquals(1, successes(outcomes), "成功次数应为 1，实际结果：" + describe(outcomes));
        assertEquals(1, conflicts(outcomes),
                "后到的一方没有被「已处理」挡下（说明 FOR UPDATE 没生效）：" + describe(outcomes));

        PullOffTask pull = pullOffTaskMapper.selectById(pullOffTaskId);
        assertEquals("RESOLVED", pull.getStatus());
        assertTrue(pull.getResolvedAt() != null, "RESOLVED 必须带 resolved_at，否则收口时刻无法举证");
    }

    // ---------- 播种 ----------

    /** APPROVED 单 + 一行盘亏（gap_qty=-2、gap_amount=-200 分）待处置。 */
    private long seedApprovedSheetWithGapLine() {
        String yearMonth = "2099-0" + (System.nanoTime() % 9 + 1);
        WarehouseMonthlyCloseSheet sheet = new WarehouseMonthlyCloseSheet();
        sheet.setWarehouseId("WH-IT-ROWLOCK");
        sheet.setYearMonth(yearMonth);
        sheet.setStatus(WarehouseMonthlyCloseSheet.STATUS_APPROVED);
        sheet.setOpeningSource("BOOK");
        sheet.setLossQty(2);
        sheet.setSurplusQty(0);
        sheet.setLossAmountCents(200L);
        sheet.setSurplusAmountCents(0L);
        sheet.setLineCount(1);
        sheet.setApprovedBy(OPERATOR_ID);
        sheet.setApprovedAt(Instant.now());
        sheet.setCreatedAt(Instant.now());
        sheet.setUpdatedAt(Instant.now());
        sheetMapper.insert(sheet);

        WarehouseMonthlyCloseLine line = new WarehouseMonthlyCloseLine();
        line.setCloseId(sheet.getCloseId());
        line.setSkuId(SKU_ID);
        line.setSkuName("行锁 IT 商品");
        line.setOpeningQty(10);
        line.setPurchaseInQty(0);
        line.setTransferInQty(0);
        line.setTransferOutQty(0);
        line.setRestockQty(0);
        line.setReturnQty(0);
        line.setLossQty(0);
        line.setExpectedQty(10);
        line.setCountedQty(8);
        line.setGapQty(-2);
        line.setGapAmountCents(-200L);
        line.setGapDisposition(WarehouseMonthlyCloseLine.DISP_PENDING);
        line.setUnitPriceCents(100L);
        lineMapper.insert(line);
        return sheet.getCloseId();
    }

    private long seedOpenPullOffTask() {
        PullOffTask pull = new PullOffTask();
        pull.setDeviceId(DEVICE_ID);
        pull.setSkuId(SKU_ID);
        pull.setQuantity(2);
        pull.setReason("EXPIRY");
        pull.setStatus("OPEN");
        pull.setCreatedAt(Instant.now());
        pullOffTaskMapper.insert(pull);
        return pull.getTaskId();
    }

    // ---------- 并发脚手架 ----------

    private interface Call {
        Object get() throws Exception;
    }

    /** 两个线程在同一个闸门前就位，同时放行打同一行。 */
    private List<Throwable> runTwoThreads(Call call) throws Exception {
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> outcomes = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    try {
                        call.get();
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            for (Future<Throwable> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        return outcomes;
    }

    private static int successes(List<Throwable> outcomes) {
        return (int) outcomes.stream().filter(t -> t == null).count();
    }

    private static int conflicts(List<Throwable> outcomes) {
        return (int) outcomes.stream()
                .filter(t -> t instanceof ResponseStatusException e
                        && e.getStatusCode() == HttpStatus.CONFLICT)
                .count();
    }

    private static String describe(List<Throwable> outcomes) {
        return outcomes.stream()
                .map(t -> t == null ? "OK" : t.getClass().getSimpleName() + ":" + t.getMessage())
                .toList()
                .toString();
    }
}
