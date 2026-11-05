package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.PaymentOperationTypes;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.service.OrderPaymentService;
import org.junit.jupiter.api.BeforeEach;
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
 * MA-161 A2：组合单（钱包冲抵 + 渠道补缴）退款后，**四层账必须自洽**——真 Postgres、真流水、
 * 真 {@code OrderPaymentService} 退款链（prepare → finalize），不是纯 mock 的金额演算。
 *
 * <p>四层：
 * <ol>
 *   <li>渠道实扣：每个渠道 COMPLETED 入账（{@link PaymentOperationTypes#INBOUND_TYPES_SQL_LIST}）之和；</li>
 *   <li>钱包冲抵：BALANCE 腿退回到钱包的金额（user_account 余额与 REFUND 流水同步）；</li>
 *   <li>退款上限：每笔渠道退款 ≤ 该渠道实扣，总额 ≤ 净实扣（{@code resolveOriginalChargeTotalCents} 按渠道取上限）；</li>
 *   <li>痕迹行合计：所有 REFUND 流水之和 == 本次退款额 == {@code cabinet_order.refunded_cents}。</li>
 * </ol>
 *
 * <p>钉住的第二个不变量（A2 期间新发现并修复）：**重试不得重复累加 refunded_cents**。
 * 拆腿后每条腿的幂等键带腿段（{@code :渠道} / {@code :BALANCE}），prepare 的基键短路
 * （{@code isCompleted(baseKey)}）在渠道腿落库前不成立；渠道失败后再次 prepare 会重走余额腿——
 * 账本幂等但 refunded_cents 不幂等，第 4 层当场穿孔。本 IT 在 finalize 之前再 prepare 一次复现。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class OrderPaymentComboRefundLedgerSqlIT {

    private static final String PREFIX = "COMBO-IT-";
    private static final long USER_FULL = 740001L;
    private static final long USER_PARTIAL = 740002L;

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
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
        // 让 prepare 走「真实渠道只准备不调 HTTP」的分支（finalize 由测试直接调用，不发请求）；
        // 私钥只在使用签名时才解析，故 dummy 值不会被读取。
        registry.add("aicabinet.wechat-pay.enabled", () -> "true");
        registry.add("aicabinet.wechat-pay.app-id", () -> "wx-it-app");
        registry.add("aicabinet.wechat-pay.mch-id", () -> "1900000001");
        registry.add("aicabinet.wechat-pay.notify-url", () -> "https://example.invalid/notify");
        registry.add("aicabinet.wechat-pay.api-v3-key", () -> "it-api-v3-key-32-bytes-placeholder");
        registry.add("aicabinet.wechat-pay.merchant-serial-no", () -> "IT-SERIAL");
        registry.add("aicabinet.wechat-pay.private-key", () -> "IT-DUMMY-KEY");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderPaymentService service;

    @Autowired
    private CabinetOrderMapper orderRepository;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM payment_operation WHERE order_id LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM cabinet_order WHERE order_id LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM shopping_session WHERE session_id LIKE ?", PREFIX + "%");
        seedUser(USER_FULL, "17400000001");
        seedUser(USER_PARTIAL, "17400000002");
        jdbc.update("INSERT INTO device_info (device_id) VALUES ('DEV-COMBO-IT') ON CONFLICT (device_id) DO NOTHING");
    }

    private void seedUser(long userId, String phone) {
        jdbc.update("INSERT INTO user_info (user_id, phone_number) VALUES (?, ?) "
                + "ON CONFLICT (user_id) DO NOTHING", userId, phone);
        jdbc.update("INSERT INTO user_account (user_id, balance_cents) VALUES (?, 0) "
                + "ON CONFLICT (user_id) DO UPDATE SET balance_cents = 0", userId);
    }

    /** 组合单：钱包冲抵 {@code walletCents}（BALANCE）+ 微信补缴 {@code channelCents}。 */
    private String seedComboOrder(String orderId, long userId, int walletCents, int channelCents) {
        jdbc.update("INSERT INTO shopping_session (session_id, user_id, device_id, state) "
                + "VALUES (?, ?, 'DEV-COMBO-IT', 'COMPLETED')", PREFIX + "S-" + orderId, userId);
        jdbc.update("INSERT INTO cabinet_order (order_id, session_id, user_id, device_id, total_amount_cents, "
                        + "status, pay_channel, pay_trade_no, inventory_deducted, refunded_cents, paid_amount_cents) "
                        + "VALUES (?, ?, ?, 'DEV-COMBO-IT', ?, 'PAID', 'WECHAT', ?, TRUE, 0, ?)",
                orderId, PREFIX + "S-" + orderId, userId, walletCents + channelCents,
                "WX-" + orderId, walletCents + channelCents);
        seedCharge(orderId + "-BAL", orderId, userId, "BALANCE", walletCents);
        seedCharge(orderId + "-WX", orderId, userId, "WECHAT", channelCents);
        return orderId;
    }

    private void seedCharge(String operationId, String orderId, long userId, String channel, int cents) {
        jdbc.update("INSERT INTO payment_operation (operation_id, order_id, user_id, operation_type, amount_cents, "
                        + "channel, status, idempotency_key) VALUES (?, ?, ?, 'CHARGE', ?, ?, 'COMPLETED', ?)",
                operationId, orderId, userId, cents, channel, "IT-" + operationId);
    }

    // ---------- 四层取数 ----------

    private int charged(String orderId, String channel) {
        Integer v = jdbc.queryForObject("SELECT COALESCE(SUM(amount_cents), 0) FROM payment_operation "
                        + "WHERE order_id = ? AND status = 'COMPLETED' AND channel = ? "
                        + "AND operation_type IN (" + PaymentOperationTypes.INBOUND_TYPES_SQL_LIST + ")",
                Integer.class, orderId, channel);
        return v == null ? 0 : v;
    }

    private int refunded(String orderId, String channel) {
        Integer v = jdbc.queryForObject("SELECT COALESCE(SUM(amount_cents), 0) FROM payment_operation "
                        + "WHERE order_id = ? AND status = 'COMPLETED' AND channel = ? "
                        + "AND operation_type = 'REFUND'",
                Integer.class, orderId, channel);
        return v == null ? 0 : v;
    }

    private int refundRowsTotal(String orderId) {
        Integer v = jdbc.queryForObject("SELECT COALESCE(SUM(amount_cents), 0) FROM payment_operation "
                + "WHERE order_id = ? AND status = 'COMPLETED' AND operation_type = 'REFUND'",
                Integer.class, orderId);
        return v == null ? 0 : v;
    }

    private int refundedCents(String orderId) {
        Integer v = jdbc.queryForObject(
                "SELECT refunded_cents FROM cabinet_order WHERE order_id = ?", Integer.class, orderId);
        return v == null ? -1 : v;
    }

    private int walletBalance(long userId) {
        Integer v = jdbc.queryForObject(
                "SELECT balance_cents FROM user_account WHERE user_id = ?", Integer.class, userId);
        return v == null ? -1 : v;
    }

    private int opCount(String orderId, String idemKey) {
        Integer v = jdbc.queryForObject("SELECT COUNT(*) FROM payment_operation "
                + "WHERE order_id = ? AND idempotency_key = ?", Integer.class, orderId, idemKey);
        return v == null ? 0 : v;
    }

    private CabinetOrder reload(String orderId) {
        return orderRepository.findById(orderId).orElseThrow();
    }

    // ---------- 用例 ----------

    @Test
    @DisplayName("组合单全额退：渠道腿=渠道实扣、钱包腿返余额；四层对账 + 重试不重复累计")
    void comboFullRefund_reconcilesFourLayers_andRetryDoesNotDoubleCount() {
        String orderId = seedComboOrder(PREFIX + "FULL", USER_FULL, 600, 400);

        // prepare：余额腿 600 就地退钱包；渠道腿 400 交给 live 分支（本测试不调 HTTP，直接 finalize）
        OrderPaymentService.OrderRefundPrep prep = service.prepareOrderRefund(orderId, 1000, "组合单全退 IT");
        assertEquals(1000, prep.refundCents(), "退款总额");
        assertEquals(400, prep.channelRefundCents(), "渠道腿必须等于微信实扣 400，不能把整笔压给微信");
        assertEquals(600, prep.balanceLegCents(), "其余 600 由钱包腿承担");
        assertTrue(prep.needsLiveChannel(), "微信已配置 ⇒ 渠道腿走 live 分支（HTTP 在事务外，由调用方执行）");
        assertEquals(400, prep.originalChargeTotalCents(), "渠道退款上限必须按渠道取实扣额 400");
        assertEquals(600, refundedCents(orderId), "prepare 完成后只累加了余额腿");
        assertEquals(600, walletBalance(USER_FULL), "钱包冲抵腿 600 必须退回用户余额");

        // 重试场景：渠道 HTTP 失败后再次 prepare（基键未完成）——余额腿不得二次累加
        OrderPaymentService.OrderRefundPrep retry = service.prepareOrderRefund(orderId, 1000, "组合单全退 IT");
        assertEquals(prep.idemKey(), retry.idemKey(),
                "重试必须复用同一幂等键：键一变 deterministicRefundNo 就变，微信侧会当成另一笔新退款（双退风险）");
        assertEquals(400, retry.refundCents(), "重试只续退剩余渠道腿（余额腿已落账）");
        assertEquals(400, retry.channelRefundCents());
        assertEquals(0, retry.balanceLegCents(), "余额腿不得二次计入");
        assertEquals(600, refundedCents(orderId),
                "重试后 refunded_cents 仍应为 600；变大即余额腿被重复累加（痕迹行合计会少于已退额）");
        assertEquals(600, walletBalance(USER_FULL), "账本幂等：钱包不得二次入账");
        assertEquals(1, opCount(orderId, prep.idemKey() + ":BALANCE"), "余额腿流水只应一行");

        service.finalizeLiveOrderRefund(retry, "SUCCESS");
        assertEquals(1000, refundedCents(orderId));

        // 层 1：渠道实扣
        assertEquals(400, charged(orderId, "WECHAT"));
        assertEquals(600, charged(orderId, "BALANCE"));
        // 层 2：钱包冲抵（余额腿退回 600；live 渠道腿不落钱包）
        assertEquals(600, refunded(orderId, "BALANCE"));
        assertEquals(600, walletBalance(USER_FULL));
        // 层 3：退款上限（每个渠道的退款 ≤ 该渠道实扣）
        assertEquals(400, refunded(orderId, "WECHAT"));
        assertTrue(refunded(orderId, "WECHAT") <= charged(orderId, "WECHAT"));
        assertTrue(refunded(orderId, "BALANCE") <= charged(orderId, "BALANCE"));
        assertTrue(refundRowsTotal(orderId) <= charged(orderId, "WECHAT") + charged(orderId, "BALANCE"));
        // 层 4：痕迹行合计 == 退款额 == 订单已退额
        assertEquals(1000, refundRowsTotal(orderId));
        assertEquals(1000, refundedCents(orderId));
        // live 支路的渠道腿用**基键**落痕（finalize）；`:渠道` 段只出现在本地/mock 支路。
        // 两者都不得多写：基键 1 条 + 余额腿 1 条 == 两层痕迹，不多不少。
        assertEquals(0, opCount(orderId, "REFUND:" + orderId + ":1000:WECHAT"),
                "live 路径不应再写一份 :WECHAT 腿（会与基键重复计退款）");
        assertEquals(1, opCount(orderId, "REFUND:" + orderId + ":1000:BALANCE"), "余额腿幂等键形状");
        Integer baseRowAmount = jdbc.queryForObject("SELECT amount_cents FROM payment_operation "
                + "WHERE order_id = ? AND idempotency_key = ?", Integer.class, orderId, prep.idemKey());
        assertEquals(400, baseRowAmount == null ? -1 : baseRowAmount,
                "基键行只承载渠道腿 400（余额腿另有 :BALANCE 行），两条相加才是退款额");
        assertEquals(1, opCount(orderId, "REFUND:" + orderId + ":1000"), "live finalize 落基键，作为整笔重放短路");

        // finalize 之后同额再退：应整体短路（skipped），金额与流水都不动
        OrderPaymentService.OrderRefundPrep replay = service.prepareOrderRefund(orderId, 1000, "组合单全退 IT");
        assertTrue(replay.skipped(), "同额重放必须短路");
        assertEquals(1000, refundedCents(orderId));
        assertEquals(1000, refundRowsTotal(orderId));
        assertEquals(600, walletBalance(USER_FULL));
    }

    @Test
    @DisplayName("组合单部分退：渠道腿先吃满渠道实扣，剩余才动钱包腿")
    void comboPartialRefund_channelLegCappedAtItsCharge() {
        String orderId = seedComboOrder(PREFIX + "PART", USER_PARTIAL, 600, 400);

        OrderPaymentService.OrderRefundPrep prep = service.prepareOrderRefund(orderId, 500, "组合单部分退 IT");
        assertEquals(500, prep.refundCents());
        assertEquals(400, prep.channelRefundCents(), "部分退 500 时渠道腿吃满其 400 实扣");
        assertEquals(100, prep.balanceLegCents(), "剩余 100 走钱包腿");
        assertEquals(100, walletBalance(USER_PARTIAL));
        assertEquals(100, refundedCents(orderId));

        service.finalizeLiveOrderRefund(prep, "SUCCESS");

        assertEquals(500, refundedCents(orderId));
        assertEquals(500, refundRowsTotal(orderId), "痕迹行合计必须等于退款额");
        assertEquals(400, refunded(orderId, "WECHAT"));
        assertEquals(100, refunded(orderId, "BALANCE"));
        assertEquals(100, walletBalance(USER_PARTIAL));
    }
}
