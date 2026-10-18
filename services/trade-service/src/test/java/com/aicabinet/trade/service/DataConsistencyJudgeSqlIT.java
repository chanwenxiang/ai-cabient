package com.aicabinet.trade.service;

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 巡检**判据 SQL 在真库上的行为**（2026-10-18，隔离栈实跑发现 8 条报警里 6 条假阳性后补）。
 *
 * <p>既有 {@code DataConsistencyServiceTest} 桩的是 {@code JdbcTemplate}、断言的是捕获到的 SQL 字符串，
 * 而判据的缺陷恰恰在 SQL 语义里（F-10 同族）——只有把真数据喂给真 Postgres、跑真判据、
 * 读 {@code data_consistency_record} 才算证明。断言一律读库，不抄 SQL。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class DataConsistencyJudgeSqlIT {

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
    private DataConsistencyService service;

    // ---------- seed helpers ----------

    private void seedOrder(String orderId, String status, int totalCents, boolean inventoryDeducted) {
        jdbc.update("INSERT INTO user_info (user_id, phone_number) VALUES (700001, '13900700001') "
                + "ON CONFLICT (user_id) DO NOTHING");
        jdbc.update("INSERT INTO device_info (device_id) VALUES ('DEV-JUDGE') ON CONFLICT (device_id) DO NOTHING");
        jdbc.update("INSERT INTO shopping_session (session_id, user_id, device_id, state) "
                + "VALUES (?, 700001, 'DEV-JUDGE', 'COMPLETED')", "S-" + orderId);
        jdbc.update("INSERT INTO cabinet_order (order_id, session_id, user_id, device_id, total_amount_cents, "
                        + "status, pay_channel, inventory_deducted, refunded_cents, paid_amount_cents) "
                        + "VALUES (?, ?, 700001, 'DEV-JUDGE', ?, ?, 'BALANCE', ?, ?, ?)",
                orderId, "S-" + orderId, totalCents, status, inventoryDeducted,
                "REFUNDED".equals(status) ? totalCents : 0,
                "REFUNDED".equals(status) ? totalCents : 0);
    }

    private void seedPayment(String operationId, String orderId, String type, int amountCents) {
        jdbc.update("INSERT INTO payment_operation (operation_id, order_id, operation_type, amount_cents, "
                        + "channel, status, idempotency_key, user_id) "
                        + "VALUES (?, ?, ?, ?, 'BALANCE', 'COMPLETED', ?, 700001)",
                operationId, orderId, type, amountCents, "judge-" + operationId);
    }

    private void seedWalletAccount(String domain, String ownerId, int balanceCents, int frozenCents) {
        jdbc.update("INSERT INTO wallet_account (domain, owner_id, balance_cents, frozen_cents) VALUES (?, ?, ?, ?)",
                domain, ownerId, balanceCents, frozenCents);
    }

    /** 一条流水；{@code createdAt} 传相对秒偏移，保证链式判据的排序确定。 */
    private void seedLedger(String domain, String ownerId, String entryType, int amountCents,
                            int balanceAfter, int frozenAfter, int secondOffset) {
        jdbc.update("INSERT INTO wallet_ledger (domain, owner_id, entry_type, amount_cents, balance_after, "
                        + "frozen_after, remark, created_at) VALUES (?, ?, ?, ?, ?, ?, 'JUDGE-PROBE', "
                        + "TIMESTAMP '2026-10-18 00:00:00Z' + (? || ' seconds')::interval)",
                domain, ownerId, entryType, amountCents, balanceAfter, frozenAfter, secondOffset);
    }

    private boolean hasFail(String checkType, String checkKey) {
        List<String> hits = jdbc.queryForList(
                "SELECT check_key FROM data_consistency_record WHERE check_type = ? AND check_key = ? "
                        + "AND status = 'FAIL'", String.class, checkType, checkKey);
        return !hits.isEmpty();
    }

    // ---------- PAYMENT_AMOUNT：PREAUTH_CAPTURE 是已完成入账（与 F-10 同源的第二副本） ----------

    @Test
    @DisplayName("PAID 单只写 PREAUTH_CAPTURE（预授权冲抵）不得报 PAYMENT_AMOUNT")
    void preauthCaptureOnlyCountsAsPaid() {
        String orderId = "JUDGE-PA-PREAUTH";
        seedOrder(orderId, "PAID", 486, true);
        seedPayment("OP-PREAUTH-1", orderId, "PREAUTH_CAPTURE", 486);

        service.checkPaymentConsistency();

        assertFalse(hasFail("PAYMENT_AMOUNT", orderId),
                "预授权冲抵行就是余额单的入账凭据，判据漏计它会恒假阳性（F-10 同族的巡检副本）");
    }

    @Test
    @DisplayName("REFUNDED 单 capture+refund 净额为 0，不得报 PAYMENT_AMOUNT")
    void preauthCaptureFullyRefundedIsZero() {
        String orderId = "JUDGE-PA-REFUND";
        seedOrder(orderId, "REFUNDED", 686, true);
        seedPayment("OP-PREAUTH-2", orderId, "PREAUTH_CAPTURE", 686);
        seedPayment("OP-REFUND-2", orderId, "REFUND", 686);

        service.checkPaymentConsistency();

        assertFalse(hasFail("PAYMENT_AMOUNT", orderId),
                "全额退后净入账应为 0；口径不含 PREAUTH_CAPTURE 时会算成 -686");
    }

    @Test
    @DisplayName("真不平仍须报：PAID 单 CHARGE 少于订单头")
    void realUnderpaymentStillAlarms() {
        String orderId = "JUDGE-PA-REAL";
        seedOrder(orderId, "PAID", 700, true);
        seedPayment("OP-CHARGE-3", orderId, "CHARGE", 500);

        service.checkPaymentConsistency();

        assertTrue(hasFail("PAYMENT_AMOUNT", orderId), "补 PREAUTH_CAPTURE 不能把真缺收款也洗掉");
    }

    // ---------- CROSS_LINK/RFINV：从未扣过库存的单不该被要求有退货流水 ----------

    @Test
    @DisplayName("inventory_deducted=false 的退货退款不要求 REFUND/REFUND_KEPT 库存流水")
    void undeductedRefundNeedsNoInventoryMovement() {
        String orderId = "JUDGE-RFINV-NO";
        seedOrder(orderId, "REFUNDED", 686, false);
        seedPayment("OP-REFUND-4", orderId, "REFUND", 686);

        service.checkCrossLinkConsistency();

        assertFalse(hasFail("CROSS_LINK", "RFINV|" + orderId),
                "没扣过库存 ⇒ 没有回架流水才是正确形态（F3-FIND-1 修法 B）");
    }

    @Test
    @DisplayName("inventory_deducted=true 却缺退货库存流水，仍须报 RFINV")
    void deductedRefundWithoutMovementStillAlarms() {
        String orderId = "JUDGE-RFINV-YES";
        seedOrder(orderId, "REFUNDED", 686, true);
        seedPayment("OP-REFUND-5", orderId, "REFUND", 686);

        service.checkCrossLinkConsistency();

        assertTrue(hasFail("CROSS_LINK", "RFINV|" + orderId), "守卫不能反把「扣过库存却没回架流水」也免了");
    }

    // ---------- STAFF 域：三域同表后判据必须覆盖补货员钱包 ----------

    @Test
    @DisplayName("STAFF 钱包余额与流水不平必须报警（统一后三域同表，判据不能再只覆盖商户/线长）")
    void staffWalletImbalanceAlarms() {
        seedWalletAccount("STAFF", "900777", 500, 0);
        seedLedger("STAFF", "900777", "COMMISSION", 300, 300, 0, 1);
        seedLedger("STAFF", "900777", "COMMISSION", 100, 400, 0, 2);

        service.runConsistencyCheck();

        assertTrue(hasFail("STAFF_WALLET", "900777"), "补货员钱包缺对账 ⇒ 记错也无人查（V388 统一后属白送的红利）");
        assertTrue(hasFail("STAFF_WALLET_LEDGER_SUM", "900777"), "Σ资金流水 400 ≠ 余额 500 必须检出");
    }

    @Test
    @DisplayName("STAFF 钱包账平（含提现冻结备忘行）不得报警")
    void staffWalletConsistentStaysSilent() {
        seedWalletAccount("STAFF", "900888", 600, 600);
        seedLedger("STAFF", "900888", "COMMISSION", 600, 600, 0, 11);
        seedLedger("STAFF", "900888", "WITHDRAW_FREEZE", -600, 600, 600, 12);

        service.runConsistencyCheck();

        assertFalse(hasFail("STAFF_WALLET", "900888"), "冻结备忘行不动总余额，最近流水口径与 Σ 剔除口径都应按 600 平");
        assertFalse(hasFail("STAFF_WALLET_LEDGER_SUM", "900888"));
    }

    // ---------- WALLET_LEDGER_CHAIN：Σ 与最近快照同时失明的中间行改写 ----------

    @Test
    @DisplayName("中间行 balance_after 被改写但首尾恰好抵消 ⇒ Σ/最近快照全绿，链判据必须报")
    void chainBreakHiddenFromSumAndLatestChecks() {
        String owner = "910424";
        // 正确链：300 → 500 → 800。这里把第 2 行写成 400、第 3 行仍写回 800
        seedWalletAccount("LINE", owner, 800, 0);
        seedLedger("LINE", owner, "COMMISSION", 300, 300, 0, 21);
        seedLedger("LINE", owner, "COMMISSION", 200, 400, 0, 22);
        seedLedger("LINE", owner, "COMMISSION", 300, 800, 0, 23);

        service.checkWalletLedgerSumConsistency("LINE");
        service.checkWalletLatestBalanceConsistency("LINE");
        assertFalse(hasFail("LINE_WALLET_LEDGER_SUM", owner), "前提核对：Σ(amount)=800=余额");
        assertFalse(hasFail("LINE_WALLET", owner), "前提核对：最近一行 balance_after=800=余额");

        service.checkWalletLedgerChainConsistency();

        assertTrue(chainFailCount("LINE", owner) >= 1,
                "两条老判据只看首尾，中间行被改写而恰好抵消时永久静默 —— 这正是链判据存在的理由");
    }

    @Test
    @DisplayName("真实提现形态（收款→冻结→打款净额+手续费）链判据必须静默")
    void withdrawLifecycleKeepsChainSilent() {
        String owner = "910425";
        // 写侧口径（lessons：freeze/release 不动 balance；打款 balance/frozen 同减毛额，
        // 流水记 WITHDRAW_PAID 净额 + WITHDRAW_FEE 手续费）——按 44 行隔离栈真流水导出
        seedWalletAccount("MERCHANT", owner, 500, 0);
        seedLedger("MERCHANT", owner, "SETTLEMENT", 1000, 1000, 0, 31);
        seedLedger("MERCHANT", owner, "WITHDRAW_FREEZE", -500, 1000, 500, 32);
        seedLedger("MERCHANT", owner, "WITHDRAW_PAID", -497, 503, 3, 33);
        seedLedger("MERCHANT", owner, "WITHDRAW_FEE", -3, 500, 0, 34);

        service.checkWalletLedgerChainConsistency();

        assertEquals(0, chainFailCount("MERCHANT", owner),
                "备忘行 effect 记 0、打款两行按净额+手续费递推，正常提现链不能被判成断链（否则是新的恒假阳性）");
        service.checkWalletLedgerSumConsistency("MERCHANT");
        assertFalse(hasFail("MERCHANT_WALLET_LEDGER_SUM", owner), "剔除备忘行后 Σ=1000-497-3=500=余额");
    }

    /** 链判据的 checkKey 是 {@code domain|ledger_id}，按 owner 反查流水行统计在案 FAIL。 */
    private int chainFailCount(String domain, String ownerId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM data_consistency_record r "
                        + "JOIN wallet_ledger l ON l.domain = split_part(r.check_key, '|', 1) "
                        + "AND l.ledger_id::text = split_part(r.check_key, '|', 2) "
                        + "WHERE r.check_type = 'WALLET_LEDGER_CHAIN' AND r.status = 'FAIL' "
                        + "AND l.domain = ? AND l.owner_id = ?",
                Integer.class, domain, ownerId);
        return count == null ? 0 : count;
    }
}
