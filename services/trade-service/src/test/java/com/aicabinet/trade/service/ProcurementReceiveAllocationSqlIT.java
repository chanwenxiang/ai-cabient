package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PurchaseOrder;
import com.aicabinet.trade.domain.PurchaseOrderLine;
import com.aicabinet.trade.mapper.PurchaseReceiveAllocationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class ProcurementReceiveAllocationSqlIT {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(
            org.testcontainers.utility.DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
        registry.add("aicabinet.device-service.url", () -> "http://127.0.0.1:1");
    }

    private static final String SUPPLIER = "SUP-IT-PROC-ALLOC";
    private static final String WH_A = "WH-IT-PROC-A";
    private static final String WH_B = "WH-IT-PROC-B";
    private static final String SKU = "SKU-IT-PROC-ALLOC";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PurchaseReceiveAllocationMapper allocationMapper;
    @Autowired private PlatformTransactionManager transactionManager;

    private WarehouseService warehouseService;
    private ProcurementService service;
    private long orderId;
    private long lineId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM warehouse_movement WHERE sku_id = ?", SKU);
        jdbc.update("DELETE FROM purchase_receive_allocation WHERE sku_id = ?", SKU);
        jdbc.update("DELETE FROM purchase_order_line WHERE sku_id = ?", SKU);
        jdbc.update("DELETE FROM purchase_order WHERE supplier_id = ?", SUPPLIER);
        jdbc.update("DELETE FROM warehouse_inventory WHERE sku_id = ?", SKU);
        jdbc.update("DELETE FROM sku_catalog WHERE sku_id = ?", SKU);
        jdbc.update("DELETE FROM warehouse WHERE warehouse_id IN (?, ?)", WH_A, WH_B);
        jdbc.update("DELETE FROM supplier WHERE supplier_id = ?", SUPPLIER);

        jdbc.update("INSERT INTO supplier (supplier_id, supplier_name) VALUES (?, ?)", SUPPLIER, "采购分配 IT");
        jdbc.update("INSERT INTO sku_catalog (sku_id, sku_name, price_cents) VALUES (?, ?, 100)",
                SKU, "采购分配 IT 商品");
        jdbc.update("INSERT INTO warehouse (warehouse_id, warehouse_name) VALUES (?, ?)", WH_A, "采购分配 IT 仓 A");
        jdbc.update("INSERT INTO warehouse (warehouse_id, warehouse_name) VALUES (?, ?)", WH_B, "采购分配 IT 仓 B");
        orderId = jdbc.queryForObject("INSERT INTO purchase_order (supplier_id, warehouse_id, status, notes) "
                + "VALUES (?, ?, 'RECEIVED', 'ProcurementReceiveAllocationSqlIT') RETURNING purchase_order_id",
                Long.class, SUPPLIER, WH_A);
        lineId = jdbc.queryForObject("INSERT INTO purchase_order_line "
                        + "(purchase_order_id, sku_id, batch_no, expiry_date, ordered_qty, received_qty, unit_cost_cents) "
                        + "VALUES (?, ?, 'B1', ?, 10, 10, 100) RETURNING line_id",
                Long.class, orderId, SKU, LocalDate.now().plusDays(30));
        jdbc.update("INSERT INTO warehouse_inventory "
                + "(warehouse_id, sku_id, batch_no, expiry_date, quantity) VALUES (?, ?, 'B1', ?, 4)",
                WH_A, SKU, LocalDate.now().plusDays(30));
        jdbc.update("INSERT INTO warehouse_inventory "
                + "(warehouse_id, sku_id, batch_no, expiry_date, quantity) VALUES (?, ?, 'B2', ?, 6)",
                WH_B, SKU, LocalDate.now().plusDays(30));
        jdbc.update("INSERT INTO purchase_receive_allocation "
                + "(purchase_order_id, purchase_line_id, sku_id, warehouse_id, batch_no, received_qty) "
                + "VALUES (?, ?, ?, ?, 'B1', 4)", orderId, lineId, SKU, WH_A);
        jdbc.update("INSERT INTO purchase_receive_allocation "
                + "(purchase_order_id, purchase_line_id, sku_id, warehouse_id, batch_no, received_qty) "
                + "VALUES (?, ?, ?, ?, 'B2', 6)", orderId, lineId, SKU, WH_B);

        warehouseService = mock(WarehouseService.class);
        doAnswer(invocation -> {
            jdbc.update("UPDATE warehouse_inventory SET quantity = quantity - ?, updated_at = NOW() "
                            + "WHERE warehouse_id = ? AND sku_id = ? AND batch_no = ?",
                    invocation.getArgument(3, Integer.class), invocation.getArgument(0, String.class),
                    invocation.getArgument(1, String.class), invocation.getArgument(2, String.class));
            return null;
        }).when(warehouseService).returnPurchaseStock(
                anyString(), anyString(), anyString(), anyInt(), anyLong(), anyString(), anyString());
        service = new ProcurementService(null, null, null, null, null, null, null, null,
                warehouseService, allocationMapper, null, null, null, null, null, null);
    }

    @Test
    void returnTen_deductsOriginalWarehouseAndBatchRows() {
        PurchaseOrder order = new PurchaseOrder();
        order.setPurchaseOrderId(orderId);
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setLineId(lineId);
        line.setSkuId(SKU);
        line.setReceivedQty(10);
        line.setReturnedQty(0);
        line.setPackQty(1);

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                ReflectionTestUtils.invokeMethod(service, "returnPurchaseStockByAllocation",
                        order, line, 10, 1L, "RET-IT-PROC-ALLOC"));

        assertEquals(0, jdbc.queryForObject("SELECT quantity FROM warehouse_inventory "
                + "WHERE warehouse_id = ? AND sku_id = ? AND batch_no = 'B1'", Integer.class, WH_A, SKU));
        assertEquals(0, jdbc.queryForObject("SELECT quantity FROM warehouse_inventory "
                + "WHERE warehouse_id = ? AND sku_id = ? AND batch_no = 'B2'", Integer.class, WH_B, SKU));
        assertEquals(List.of(4, 6), jdbc.queryForList("SELECT returned_qty FROM purchase_receive_allocation "
                + "WHERE purchase_line_id = ? ORDER BY allocation_id", Integer.class, lineId));
        verify(warehouseService, times(2)).returnPurchaseStock(
                anyString(), anyString(), anyString(), anyInt(), anyLong(), anyString(), anyString());
    }

    @Test
    void v510_backfillsOnlyUniquelyMatchedHistoricalReceipts() throws Exception {
        jdbc.update("DELETE FROM purchase_receive_allocation WHERE purchase_line_id = ?", lineId);
        jdbc.update("INSERT INTO warehouse_movement "
                        + "(warehouse_id, sku_id, batch_no, movement_type, delta_qty, ref_type, ref_id) "
                        + "VALUES (?, ?, 'B1', 'PURCHASE_RECEIVE', 4, 'PURCHASE_ORDER', ?)",
                WH_A, SKU, String.valueOf(orderId));
        // 同订单同 SKU 再加一条同批次行：历史流水有两条候选，必须整条跳过，不能挑第一条。
        long ambiguousLineId = jdbc.queryForObject("INSERT INTO purchase_order_line "
                        + "(purchase_order_id, sku_id, batch_no, expiry_date, ordered_qty, received_qty, unit_cost_cents) "
                        + "VALUES (?, ?, 'B1', ?, 1, 1, 100) RETURNING line_id",
                Long.class, orderId, SKU, LocalDate.now().plusDays(30));
        jdbc.update("INSERT INTO warehouse_movement "
                        + "(warehouse_id, sku_id, batch_no, movement_type, delta_qty, ref_type, ref_id) "
                        + "VALUES (?, ?, 'B1', 'PURCHASE_RECEIVE', 1, 'PURCHASE_ORDER', ?)",
                WH_B, SKU, String.valueOf(orderId));

        String migration = new String(new org.springframework.core.io.ClassPathResource(
                "db/migration/V510__purchase_receive_allocation_backfill.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        jdbc.execute(migration);

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM purchase_receive_allocation "
                + "WHERE purchase_line_id IN (?, ?)", Integer.class, lineId, ambiguousLineId));
    }

    @Test
    void v510_backfillsUniqueSkuAndBatchWithOriginalWarehouse() throws Exception {
        jdbc.update("DELETE FROM purchase_receive_allocation WHERE purchase_line_id = ?", lineId);
        jdbc.update("UPDATE purchase_order_line SET received_qty = 4 WHERE line_id = ?", lineId);
        jdbc.update("INSERT INTO warehouse_movement "
                        + "(warehouse_id, sku_id, batch_no, movement_type, delta_qty, ref_type, ref_id) "
                        + "VALUES (?, ?, 'B1', 'PURCHASE_RECEIVE', 4, 'PURCHASE_ORDER', ?)",
                WH_A, SKU, String.valueOf(orderId));

        String migration = new String(new org.springframework.core.io.ClassPathResource(
                "db/migration/V510__purchase_receive_allocation_backfill.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        jdbc.execute(migration);

        var row = jdbc.queryForMap("SELECT warehouse_id, batch_no, received_qty FROM purchase_receive_allocation "
                + "WHERE purchase_line_id = ?", lineId);
        assertEquals(WH_A, row.get("warehouse_id"));
        assertEquals("B1", row.get("batch_no"));
        assertEquals(4, ((Number) row.get("received_qty")).intValue());
    }

    @Test
    void v510_sameSkuDifferentBatches_matchEachOriginalLine() throws Exception {
        jdbc.update("DELETE FROM purchase_receive_allocation WHERE purchase_line_id = ?", lineId);
        jdbc.update("UPDATE purchase_order_line SET received_qty = 4 WHERE line_id = ?", lineId);
        long secondLineId = jdbc.queryForObject("INSERT INTO purchase_order_line "
                        + "(purchase_order_id, sku_id, batch_no, expiry_date, ordered_qty, received_qty, unit_cost_cents) "
                        + "VALUES (?, ?, 'B2', ?, 6, 6, 100) RETURNING line_id",
                Long.class, orderId, SKU, LocalDate.now().plusDays(30));
        jdbc.update("INSERT INTO warehouse_movement "
                        + "(warehouse_id, sku_id, batch_no, movement_type, delta_qty, ref_type, ref_id) "
                        + "VALUES (?, ?, 'B1', 'PURCHASE_RECEIVE', 4, 'PURCHASE_ORDER', ?)",
                WH_A, SKU, String.valueOf(orderId));
        jdbc.update("INSERT INTO warehouse_movement "
                        + "(warehouse_id, sku_id, batch_no, movement_type, delta_qty, ref_type, ref_id) "
                        + "VALUES (?, ?, 'B2', 'PURCHASE_RECEIVE', 6, 'PURCHASE_ORDER', ?)",
                WH_B, SKU, String.valueOf(orderId));

        String migration = new String(new org.springframework.core.io.ClassPathResource(
                "db/migration/V510__purchase_receive_allocation_backfill.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        jdbc.execute(migration);

        assertEquals("B1", jdbc.queryForObject("SELECT batch_no FROM purchase_receive_allocation "
                + "WHERE purchase_line_id = ?", String.class, lineId));
        assertEquals("B2", jdbc.queryForObject("SELECT batch_no FROM purchase_receive_allocation "
                + "WHERE purchase_line_id = ?", String.class, secondLineId));
        assertEquals(List.of(4, 6), jdbc.queryForList("SELECT received_qty FROM purchase_receive_allocation "
                + "WHERE purchase_line_id IN (?, ?) ORDER BY allocation_id", Integer.class, lineId, secondLineId));
    }

    @Test
    void returnWithoutAllocations_failsClosedWith409() {
        jdbc.update("DELETE FROM purchase_receive_allocation WHERE purchase_line_id = ?", lineId);
        PurchaseOrder order = new PurchaseOrder();
        order.setPurchaseOrderId(orderId);
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setLineId(lineId);
        line.setSkuId(SKU);
        line.setReceivedQty(10);
        line.setPackQty(1);

        org.springframework.web.server.ResponseStatusException error = assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        ReflectionTestUtils.invokeMethod(service, "returnPurchaseStockByAllocation",
                                order, line, 1, 1L, "RET-IT-PROC-ALLOC")));

        assertEquals(409, error.getStatusCode().value());
        assertEquals(4, jdbc.queryForObject("SELECT quantity FROM warehouse_inventory "
                + "WHERE warehouse_id = ? AND sku_id = ? AND batch_no = 'B1'", Integer.class, WH_A, SKU));
    }
}
