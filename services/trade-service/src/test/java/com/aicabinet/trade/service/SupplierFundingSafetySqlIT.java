package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CreatePurchaseReturnRequest;
import com.aicabinet.common.dto.PaySupplierRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class SupplierFundingSafetySqlIT {

    private static final String SUPPLIER = "SUP-IT-FUNDING";
    private static final String WAREHOUSE = "WH-IT-FUNDING";
    private static final String SKU = "SKU-IT-FUNDING";

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
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
        registry.add("aicabinet.device-service.url", () -> "http://127.0.0.1:1");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SupplierPayableService payableService;
    @Autowired private ProcurementService procurementService;

    private long orderId;
    private long lineId;
    private long payableId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM supplier_payment WHERE supplier_id = ?", SUPPLIER);
        jdbc.update("DELETE FROM supplier_payable_entry WHERE supplier_id = ?", SUPPLIER);
        jdbc.update("DELETE FROM supplier_payable WHERE supplier_id = ?", SUPPLIER);
        jdbc.update("DELETE FROM purchase_return_line WHERE return_id IN "
                + "(SELECT return_id FROM purchase_return WHERE supplier_id = ?)", SUPPLIER);
        jdbc.update("DELETE FROM purchase_return WHERE supplier_id = ?", SUPPLIER);
        jdbc.update("DELETE FROM purchase_receive_allocation WHERE sku_id = ?", SKU);
        jdbc.update("DELETE FROM warehouse_movement WHERE sku_id = ?", SKU);
        jdbc.update("DELETE FROM purchase_order_line WHERE sku_id = ?", SKU);
        jdbc.update("DELETE FROM purchase_order WHERE supplier_id = ?", SUPPLIER);
        jdbc.update("DELETE FROM warehouse_inventory WHERE sku_id = ?", SKU);
        jdbc.update("DELETE FROM warehouse_manager WHERE warehouse_id = ?", WAREHOUSE);
        jdbc.update("DELETE FROM sku_catalog WHERE sku_id = ?", SKU);
        jdbc.update("DELETE FROM warehouse WHERE warehouse_id = ?", WAREHOUSE);
        jdbc.update("DELETE FROM supplier WHERE supplier_id = ?", SUPPLIER);

        jdbc.update("INSERT INTO supplier (supplier_id, supplier_name) VALUES (?, ?)", SUPPLIER, "资金安全 IT 供应商");
        jdbc.update("INSERT INTO user_info (user_id, phone_number) VALUES (9801, '13900009801') "
                + "ON CONFLICT (user_id) DO NOTHING");
        jdbc.update("INSERT INTO warehouse (warehouse_id, warehouse_name, manager_user_id) VALUES (?, ?, 9801)",
                WAREHOUSE, "资金安全 IT 仓");
        jdbc.update("INSERT INTO warehouse_manager (warehouse_id, user_id) VALUES (?, 9801)", WAREHOUSE);
        jdbc.update("INSERT INTO sku_catalog (sku_id, sku_name, price_cents) VALUES (?, ?, 100)",
                SKU, "资金安全 IT 商品");
        orderId = jdbc.queryForObject("INSERT INTO purchase_order (supplier_id, warehouse_id, status, notes) "
                + "VALUES (?, ?, 'RECEIVED', 'SupplierFundingSafetySqlIT') RETURNING purchase_order_id",
                Long.class, SUPPLIER, WAREHOUSE);
        lineId = jdbc.queryForObject("INSERT INTO purchase_order_line "
                        + "(purchase_order_id, sku_id, batch_no, expiry_date, ordered_qty, received_qty, unit_cost_cents) "
                        + "VALUES (?, ?, 'B1', ?, 10, 10, 100) RETURNING line_id",
                Long.class, orderId, SKU, LocalDate.now().plusDays(30));
        jdbc.update("INSERT INTO warehouse_inventory "
                + "(warehouse_id, sku_id, batch_no, expiry_date, quantity) VALUES (?, ?, 'B1', ?, 10)",
                WAREHOUSE, SKU, LocalDate.now().plusDays(30));
        jdbc.update("INSERT INTO purchase_receive_allocation "
                + "(purchase_order_id, purchase_line_id, sku_id, warehouse_id, batch_no, received_qty) "
                + "VALUES (?, ?, ?, ?, 'B1', 10)", orderId, lineId, SKU, WAREHOUSE);
        payableId = jdbc.queryForObject("INSERT INTO supplier_payable "
                        + "(supplier_id, purchase_order_id, warehouse_id, amount_cents, paid_amount_cents, status, due_date) "
                        + "VALUES (?, ?, ?, 10000, 0, 'UNPAID', ?) RETURNING payable_id",
                Long.class, SUPPLIER, orderId, WAREHOUSE, LocalDate.now().plusDays(30));
    }

    @Test
    void sameIdempotencyKey_retryAddsPaymentAndPaidAmountOnlyOnce() {
        PaySupplierRequest request = new PaySupplierRequest(4000L, "第一次响应丢失", "PAY-IT-RETRY");

        payInTx(request);
        payInTx(request);

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM supplier_payment WHERE payable_id = ?", Integer.class, payableId));
        assertEquals(4000L, jdbc.queryForObject(
                "SELECT paid_amount_cents FROM supplier_payable WHERE payable_id = ?", Long.class, payableId));
    }

    @Test
    void sameIdempotencyKey_concurrentRequestsStillAccrueOnce() throws Exception {
        PaySupplierRequest request = new PaySupplierRequest(4000L, "并发重试", "PAY-IT-RACE");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> {
                        ready.countDown();
                        go.await(10, TimeUnit.SECONDS);
                        payInTx(request);
                        return null;
                    }),
                    pool.submit(() -> {
                        ready.countDown();
                        go.await(10, TimeUnit.SECONDS);
                        payInTx(request);
                        return null;
                    }));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM supplier_payment WHERE payable_id = ?", Integer.class, payableId));
        assertEquals(4000L, jdbc.queryForObject(
                "SELECT paid_amount_cents FROM supplier_payable WHERE payable_id = ?", Long.class, payableId));
    }

    @Test
    void paidPurchaseReturn_rejectsAndRollsBackInventoryAllocationAndDocuments() {
        jdbc.update("UPDATE supplier_payable SET paid_amount_cents = 10000, status = 'PAID' WHERE payable_id = ?",
                payableId);
        jdbc.update("INSERT INTO supplier_payment "
                + "(supplier_id, payable_id, amount_cents, operator_id, notes, idempotency_key) "
                + "VALUES (?, ?, 10000, 9801, '已付 IT', 'PAY-IT-PAID')", SUPPLIER, payableId);
        CreatePurchaseReturnRequest request = new CreatePurchaseReturnRequest(
                orderId, "已付后退货必须拒绝", "QUALITY", "SUPPLIER", true,
                List.of(new CreatePurchaseReturnRequest.PurchaseReturnLineRequest(lineId, 2)));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        ReflectionTestUtils.invokeMethod((Object) AopTestUtils.getTargetObject(procurementService),
                                "doCreatePurchaseReturn", 9801L, request)));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode(), error.getReason());
        assertTrue(error.getReason().contains("供应商退款"));
        assertEquals(10, jdbc.queryForObject(
                "SELECT quantity FROM warehouse_inventory WHERE warehouse_id = ? AND sku_id = ? AND batch_no = 'B1'",
                Integer.class, WAREHOUSE, SKU));
        assertEquals(0, jdbc.queryForObject(
                "SELECT returned_qty FROM purchase_order_line WHERE line_id = ?", Integer.class, lineId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT returned_qty FROM purchase_receive_allocation WHERE purchase_line_id = ?", Integer.class, lineId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM purchase_return WHERE purchase_order_id = ?", Integer.class, orderId));
        assertEquals(10000L, jdbc.queryForObject(
                "SELECT amount_cents FROM supplier_payable WHERE payable_id = ?", Long.class, payableId));
        assertEquals(10000L, jdbc.queryForObject(
                "SELECT paid_amount_cents FROM supplier_payable WHERE payable_id = ?", Long.class, payableId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM supplier_payable_entry WHERE payable_id = ?", Integer.class, payableId));
    }

    private void payInTx(PaySupplierRequest request) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                ReflectionTestUtils.invokeMethod((Object) AopTestUtils.getTargetObject(payableService),
                        "doPay", 9801L, payableId, request, request.idempotencyKey()));
    }
}
