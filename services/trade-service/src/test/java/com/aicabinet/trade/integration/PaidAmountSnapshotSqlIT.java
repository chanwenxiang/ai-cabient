package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.PaymentOperationTypes;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C1 快照列双定义（V384/CB-057/F-9）：{@code cabinet_order.paid_amount_cents} 的「回填口径」
 * 与「运行期冻结口径」必须逐单同值，且回填后的越界数据必须 fail-loud。
 *
 * <p>双定义指同一份公式存在两份实现：迁移里的回填 UPDATE（一次性）与
 * {@link PaymentOperationMapper#chargedCompletedCents}（运行期 {@code freezePaidAmount} 用）。
 * 两份都自称「COMPLETED 的 CHARGE/ADJUST_CHARGE/PREAUTH_CAPTURE 之和」，但字符串断言
 * （{@code DataConsistencyServiceTest}）和 Java 单测都证明不了 SQL 语义——只有把真数据喂给真
 * Postgres、跑**迁移原文**再与 Java 逐单比对，才算钉住。本 IT 直接从 classpath 读
 * {@code db/migration/V384__order_paid_amount_snapshot.sql} 抽取语句执行（V384 已应用、只读），
 * 标记缺失即红：删掉回填或护栏时本 IT 必须失败，而不是静默零命中。</p>
 *
 * <p>已知边界（本 IT 不覆盖，也不改已应用的 V384）：Java 侧逐行 {@code Math.max(0, amountCents)}
 * 是防御，而回填 SQL 原样求和。现网 {@code OrderPaymentService#applyPaymentDelta} 把负差额转
 * {@code refundOrder}（写 REFUND 行），目前的写入口不会产生负的入账行；但表上没有
 * {@code amount_cents >= 0} 约束，一旦历史库存在负入账行，两份实现就会分叉
 * （实测 CHARGE 1000 + ADJUST_CHARGE −300：Java 取 1000，回填取 700），
 * 必须人工核流水，不能靠调合计迁就。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class PaidAmountSnapshotSqlIT {

    private static final String MIGRATION = "db/migration/V384__order_paid_amount_snapshot.sql";
    private static final String ORDER_PREFIX = "SNAP-";
    private static final long USER_ID = 710001L;

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
    private JdbcTemplate jdbc;

    @Autowired
    private PaymentOperationMapper paymentOperationMapper;

    @BeforeEach
    void resetSeeds() {
        jdbc.update("DELETE FROM cabinet_order WHERE order_id LIKE ?", ORDER_PREFIX + "%");
        jdbc.update("DELETE FROM shopping_session WHERE session_id LIKE ?", ORDER_PREFIX + "%");
        jdbc.update("INSERT INTO user_info (user_id, phone_number) VALUES (?, '13900710001') "
                + "ON CONFLICT (user_id) DO NOTHING", USER_ID);
        jdbc.update("INSERT INTO device_info (device_id) VALUES ('DEV-SNAPSHOT') ON CONFLICT (device_id) DO NOTHING");
    }

    // ---------- seed helpers ----------

    private void seedOrder(String orderId, int totalCents, int refundedCents) {
        jdbc.update("INSERT INTO shopping_session (session_id, user_id, device_id, state) "
                + "VALUES (?, ?, 'DEV-SNAPSHOT', 'COMPLETED')", "SESS-" + orderId, USER_ID);
        jdbc.update("INSERT INTO cabinet_order (order_id, session_id, user_id, device_id, total_amount_cents, "
                        + "status, pay_channel, inventory_deducted, refunded_cents) "
                        + "VALUES (?, ?, ?, 'DEV-SNAPSHOT', ?, 'PAID', 'BALANCE', TRUE, ?)",
                orderId, "SESS-" + orderId, USER_ID, totalCents, refundedCents);
    }

    private void seedPayment(String operationId, String orderId, String type, String status, int amountCents) {
        jdbc.update("INSERT INTO payment_operation (operation_id, order_id, operation_type, amount_cents, "
                        + "channel, status, idempotency_key, user_id) "
                        + "VALUES (?, ?, ?, ?, 'BALANCE', ?, ?, ?)",
                operationId, orderId, type, amountCents, status, "snap-" + operationId, USER_ID);
    }

    private int frozen(String orderId) {
        Integer cents = jdbc.queryForObject(
                "SELECT paid_amount_cents FROM cabinet_order WHERE order_id = ?", Integer.class, orderId);
        return cents == null ? -1 : cents;
    }

    // ---------- 迁移原文抽取（缺标记即红） ----------

    private static String migrationSql() {
        try (InputStream in = new ClassPathResource(MIGRATION).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读不到 classpath 上的 " + MIGRATION + "，盘上文件被删或未打进测试类路径", e);
        }
    }

    /** 抽取从 {@code head} 到其后首个 {@code tail} 的原文；任一标记缺失用断言失败暴露，不静默跳过。 */
    private static String extractStatement(String head, String tail, String what) {
        String sql = migrationSql();
        int from = sql.indexOf(head);
        assertTrue(from >= 0, what + "：在 " + MIGRATION + " 里找不到起始标记「" + head + "」"
                + "——回填/护栏被删改时本 IT 必须红，不能静默跳过");
        int end = sql.indexOf(tail, from + head.length());
        assertTrue(end >= 0, what + "：起始标记之后找不到结束标记「" + tail + "」，迁移被截断？");
        String statement = sql.substring(from, end + tail.length()).strip();
        return statement.endsWith(";") ? statement.substring(0, statement.length() - 1) : statement;
    }

    private static String messageChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append('\n');
        }
        return sb.toString();
    }

    // ---------- A：回填 SQL ↔ Java 逐单同构 ----------

    @Test
    @DisplayName("V384 回填与 chargedCompletedCents 逐单同值：只认 COMPLETED 的三类入账")
    void backfillMatchesJavaChargedCompletedCents() {
        // O1：正常入账 1000+200+300，另混入退款/未完成/非购物类型——后三者都不得进毛额。
        String mixed = ORDER_PREFIX + "MIXED";
        seedOrder(mixed, 1000, 500);
        seedPayment("OP-SNAP-1", mixed, "CHARGE", "COMPLETED", 1000);
        seedPayment("OP-SNAP-2", mixed, "ADJUST_CHARGE", "COMPLETED", 200);
        seedPayment("OP-SNAP-3", mixed, "PREAUTH_CAPTURE", "COMPLETED", 300);
        seedPayment("OP-SNAP-4", mixed, "REFUND", "COMPLETED", 500);
        seedPayment("OP-SNAP-5", mixed, "CHARGE", "INITIATED", 999);
        seedPayment("OP-SNAP-6", mixed, "RECHARGE", "COMPLETED", 4000);

        // O2：单笔 CHARGE。
        String plain = ORDER_PREFIX + "PLAIN";
        seedOrder(plain, 700, 0);
        seedPayment("OP-SNAP-7", plain, "CHARGE", "COMPLETED", 700);

        // O3：从未扣过款 ⇒ 留 0（读侧回落 total）。
        String empty = ORDER_PREFIX + "EMPTY";
        seedOrder(empty, 350, 0);

        String backfill = extractStatement("UPDATE cabinet_order o", ";", "V384 回填 UPDATE");
        assertTrue(backfill.contains("status = 'COMPLETED'"), "回填必须只认 COMPLETED 流水：" + backfill);
        assertTrue(backfill.contains(PaymentOperationTypes.INBOUND_TYPES_SQL_LIST),
                "回填的入账类型清单必须与 PaymentOperationTypes 单一事实源逐字一致（G11 判据同源），实际："
                        + backfill);
        assertTrue(backfill.contains("g.gross_cents > 0"),
                "回填必须跳过 0/负毛额，否则无流水单会被写成负值：" + backfill);

        jdbc.update(backfill);

        for (String orderId : List.of(mixed, plain, empty)) {
            assertEquals(paymentOperationMapper.chargedCompletedCents(orderId), frozen(orderId),
                    "订单 " + orderId + "：V384 回填出的冻结毛额必须与运行期 chargedCompletedCents 同值"
                            + "（两份实现分叉时先收款后回填的单会出现两个「成交额」）");
        }
        assertEquals(1500, frozen(mixed), "1000+200+300；退款 500 走 refunded_cents、INITIATED 999 与 RECHARGE 4000 不进毛额");
        assertEquals(700, frozen(plain));
        assertEquals(0, frozen(empty), "无入账流水的单必须留 0；写成别的值会让读侧回落 total 的语义断掉");
    }

    // ---------- B：护栏 fail-loud ----------

    @Test
    @DisplayName("V384 护栏：冻结毛额 > 应付+已退 必须抛错点名，收敛成合法形态后放行")
    void boundGuardRejectsFrozenGrossExceedingPayablePlusRefunded() {
        String bad = ORDER_PREFIX + "BAD";
        seedOrder(bad, 500, 0);
        jdbc.update("UPDATE cabinet_order SET paid_amount_cents = 1000 WHERE order_id = ?", bad);

        String guard = extractStatement("DO $paid_amount_bound$", "$paid_amount_bound$;", "V384 冻结毛额护栏");

        DataAccessException ex = assertThrows(DataAccessException.class, () -> jdbc.execute(guard),
                "冻结 1000 > 应付 500 + 已退 0 说明流水与本单对不上（挂错单/重复扣款），"
                        + "带这种数据发佣就是资损，护栏必须拦住而不是留 NOTICE");
        String text = messageChain(ex);
        assertTrue(text.contains("V384 回填被拦"), "护栏报错必须保留可检索的前缀，实际：" + text);
        assertTrue(text.contains(bad), "护栏必须点名越界订单，否则人工无从核实，实际：" + text);

        // 合法形态：原 1000 被摊薄成应付 300 + 已退 700（V384 头注释的合法样例）⇒ 必须放行。
        jdbc.update("UPDATE cabinet_order SET total_amount_cents = 300, refunded_cents = 700 WHERE order_id = ?", bad);
        assertDoesNotThrow(() -> jdbc.execute(guard),
                "冻结毛额 = 应付 + 已退 是合法上限（退款把应付摊薄后的常态），护栏在这里误报会让合法迁移无法重跑");
    }
}
