package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.PurchaseOrderLine;
import com.aicabinet.trade.mapper.PurchaseOrderLineMapper;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V405 真库回归：{@code purchase_order_line.batch_no / expiry_date} 下单可空、收货回填。
 * <p>服务层（{@code ProcurementService.validatePurchaseLine} 的 receiving 分支）早已按
 * 「下单选填 + 收货必填」实现并有单测，但 V34 建表把两列定成 NOT NULL ⇒ 下单不填批次
 * 在真库上 23502 500（UAT §7.20 ⓱19 活栈实测）。Mockito 单测拦不住这类「列约束与服务口径相反」，
 * 本 IT 走真库钉住 insert(null)→selectById→收货回写 updateById 三链。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class PurchaseLineNullableBatchSqlIT {

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

    private static final String SUP = "IT405SUP";
    private static final String WH = "900000040500";
    private static final String SKU = "SKU-IT405";

    @Autowired
    private PurchaseOrderLineMapper lineMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** 造一张父单（supplier/warehouse/sku 用 jdbc 直插，只为本 IT 提供外键父行）。 */
    private long newOrderId(String refNo) {
        jdbc.update("INSERT INTO supplier (supplier_id, supplier_name) VALUES (?, 'IT405 supplier') "
                + "ON CONFLICT (supplier_id) DO NOTHING", SUP);
        jdbc.update("INSERT INTO warehouse (warehouse_id, warehouse_name) VALUES (?, 'IT405 wh') "
                + "ON CONFLICT (warehouse_id) DO NOTHING", WH);
        jdbc.update("INSERT INTO sku_catalog (sku_id, sku_name, price_cents) VALUES (?, 'IT405 sku', 100) "
                + "ON CONFLICT (sku_id) DO NOTHING", SKU);
        jdbc.update("INSERT INTO purchase_order (supplier_id, warehouse_id, status, ref_no) "
                + "VALUES (?, ?, 'PENDING_APPROVAL', ?)", SUP, WH, refNo);
        return jdbc.queryForObject(
                "SELECT purchase_order_id FROM purchase_order WHERE ref_no = ?", Long.class, refNo);
    }

    private PurchaseOrderLine freshLine(long orderId) {
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setPurchaseOrderId(orderId);
        line.setSkuId(SKU);
        line.setOrderedQty(5);
        line.setUnitCostCents(1200);
        return line;
    }

    @Test
    void batchAndExpiryColumnsAreNullable_afterV405() {
        List<String> stillNotNull = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'purchase_order_line' "
                        + "AND column_name IN ('batch_no', 'expiry_date') AND is_nullable = 'NO'",
                String.class);

        assertTrue(stillNotNull.isEmpty(), "V405 后两列应可空，实际仍 NOT NULL: " + stillNotNull);
    }

    @Test
    void insertWithoutBatch_persistsNull_andReceiveBackfills() {
        long orderId = newOrderId("IT405-" + System.nanoTime());

        // 修复前此处即 500：null value in column "batch_no" violates not-null constraint（23502）
        PurchaseOrderLine line = freshLine(orderId);
        lineMapper.insert(line);
        assertNotNull(line.getLineId(), "insert 后主键应回填");

        PurchaseOrderLine loaded = lineMapper.selectById(line.getLineId());
        assertNull(loaded.getBatchNo(), "下单未填批次 ⇒ 库里应为 NULL，而不是空串");
        assertNull(loaded.getExpiryDate(), "下单未填效期 ⇒ 库里应为 NULL");

        // 收货回写形态（processReceiveLine：补批次/效期后才入仓）
        loaded.setBatchNo("IT405-B1");
        loaded.setExpiryDate(LocalDate.now().plusMonths(6));
        loaded.setReceivedQty(5);
        lineMapper.updateById(loaded);

        PurchaseOrderLine after = lineMapper.selectById(line.getLineId());
        assertEquals("IT405-B1", after.getBatchNo(), "收货批次必须落库");
        assertNotNull(after.getExpiryDate(), "收货效期必须落库");

        lineMapper.deleteById(line.getLineId());
        jdbc.update("DELETE FROM purchase_order WHERE purchase_order_id = ?", orderId);
    }

    @Test
    void blankBatchOnOrderLine_isStillStoredAsGiven_notSilentlyCoerced() {
        // 服务层 trimToNull 负责把空串变 null；DB 侧不再有约束拦 null——钉住这一分工不被反向依赖
        long orderId = newOrderId("IT405-BLANK-" + System.nanoTime());
        PurchaseOrderLine line = freshLine(orderId);
        line.setBatchNo(null);
        line.setExpiryDate(null);
        lineMapper.insert(line);

        Integer nulls = jdbc.queryForObject(
                "SELECT count(*) FROM purchase_order_line WHERE line_id = ? "
                        + "AND batch_no IS NULL AND expiry_date IS NULL",
                Integer.class, line.getLineId());
        assertEquals(1, nulls, "两列应同为 NULL（而不是被默认值填成空串/今天）");

        lineMapper.deleteById(line.getLineId());
        jdbc.update("DELETE FROM purchase_order WHERE purchase_order_id = ?", orderId);
    }
}
