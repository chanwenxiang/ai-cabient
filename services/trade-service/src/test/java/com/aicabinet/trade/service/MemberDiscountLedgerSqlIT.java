package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CabinetOrderLine;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.recognition.RecognizedItem;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-029 方案 A 的「活库真单」腿：真 Postgres（Flyway V1→V425）+ 真会员档位规则 +
 * 真生产写入口 {@link SettlementOrderSupport#applyItemsToOrder} + 独立对账 SQL。
 *
 * <p>为什么必须有这条腿（单测证不到的东西）：
 * <ul>
 *   <li>{@code member_discount_cents} 自本批起才有写入者 ⇒ 只有真库能证明这一列真的落得下去、
 *       读得回来（列映射/域范围/NOT NULL 默认值）；</li>
 *   <li>会员档位（SILVER 2%）来自迁移种子，单测里 MemberService 是被 mock 的；</li>
 *   <li>巡检折后式（{@code DataConsistencyService.checkOrderConsistency}）与恒等式是 <b>SQL</b>，
 *       字符串断言与 Java 单测都证不了语义——只有把真行喂给真 Postgres 跑原文才钉得住。</li>
 * </ul>
 *
 * <p>反向护栏（本 IT 的「非零」条件）：会员档若为 0，则 ②重复扣减与 ①只扣一次在数字上无法区分，
 * 断言会退化成假绿 ⇒ 先断言档位真的减了钱（{@code memberUnit &lt; listUnit}）再谈恒等式。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("MA-029 会员折扣入账：活库真单 + 独立对账 SQL")
class MemberDiscountLedgerSqlIT {

    private static final long USER_ID = 710002L;
    private static final String DEVICE_ID = "DEV-MA029";
    private static final String SKU_ID = "SKU-MA029";
    private static final String ORDER_ID = "MA029-LEDGER-1";
    private static final String SESSION_ID = "MA029-SESSION-1";
    private static final int LIST_UNIT_CENTS = 350;
    private static final int QUANTITY = 3;

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(
            org.testcontainers.utility.DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void containerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SettlementOrderSupport orderSupport;
    @Autowired
    private CabinetOrderMapper orderRepository;
    @Autowired
    private CabinetOrderLineMapper orderLineRepository;
    @Autowired
    private MemberService memberService;

    @BeforeEach
    void seedFixture() {
        jdbc.update("INSERT INTO user_info (user_id, phone_number) VALUES (?, '13900710002') "
                + "ON CONFLICT (user_id) DO NOTHING", USER_ID);
        jdbc.update("INSERT INTO device_info (device_id) VALUES (?) ON CONFLICT (device_id) DO NOTHING",
                DEVICE_ID);
        jdbc.update("INSERT INTO sku_catalog (sku_id, sku_name, price_cents) "
                + "VALUES (?, 'MA029 会员档位真单', ?) ON CONFLICT (sku_id) DO NOTHING",
                SKU_ID, LIST_UNIT_CENTS);
        jdbc.update("INSERT INTO member (user_id, member_level) "
                + "SELECT ?, 'SILVER' WHERE NOT EXISTS (SELECT 1 FROM member WHERE user_id = ?)",
                USER_ID, USER_ID);
        jdbc.update("INSERT INTO member_level_rule (level_code, level_name, price_discount_pct) "
                + "SELECT 'SILVER', '银卡会员', 2.00 WHERE NOT EXISTS "
                + "(SELECT 1 FROM member_level_rule WHERE level_code = 'SILVER')");
        // 幂等：本类内多次调用（第二个用例复用第一个用例的写腿）时先清掉旧行
        // cabinet_order.session_id 有 FK（ON DELETE CASCADE）⇒ 先删订单再删会话
        jdbc.update("DELETE FROM cabinet_order WHERE order_id = ?", ORDER_ID);
        jdbc.update("DELETE FROM shopping_session WHERE session_id = ?", SESSION_ID);
        jdbc.update("INSERT INTO shopping_session (session_id, user_id, device_id, state) "
                + "VALUES (?, ?, ?, 'COMPLETED')", SESSION_ID, USER_ID, DEVICE_ID);
    }

    /**
     * ①入账的三件事一起钉：会员档真的落库、应付里只减过一次、恒等式在真 SQL 上平。
     */
    @Test
    void bookedMemberDiscount_persistsAndKeepsLedgerIdentity() {
        int memberUnit = memberService.applyMemberPriceDiscount(USER_ID, LIST_UNIT_CENTS);
        assertTrue(memberUnit < LIST_UNIT_CENTS,
                "SILVER 档位必须真的减钱，否则 ①/②在数字上不可区分（本 IT 会假绿）");

        CabinetOrder order = new CabinetOrder();
        order.setOrderId(ORDER_ID);
        order.setSessionId(SESSION_ID);
        order.setUserId(USER_ID);
        order.setDeviceId(DEVICE_ID);
        order.setStatus("PAID");
        order.setPayChannel("BALANCE");
        order.setInventoryDeducted(true);

        orderSupport.applyItemsToOrder(order, List.of(new RecognizedItem(SKU_ID, QUANTITY, 1f)));
        // 生产持久化路径（结算 finalize 用的同一套 mapper.save）
        orderRepository.save(order);
        for (CabinetOrderLine line : order.getLines()) {
            line.setOrderId(ORDER_ID);
            orderLineRepository.save(line);
        }

        int expectedMemberArchive = (LIST_UNIT_CENTS - memberUnit) * QUANTITY;
        int expectedLineSum = memberUnit * QUANTITY;
        assertEquals(21, expectedMemberArchive, "期望值随种子档位与单价联动（350→343 时 = 3×7）");

        LedgerRow row = readLedger(ORDER_ID);
        assertEquals(LIST_UNIT_CENTS * QUANTITY, row.gross, "gross = Σ 目录价 × 数量");
        assertEquals(expectedMemberArchive, row.member, "member = Σ(目录价 − 会员价) × 数量（留档，不是减项）");
        assertEquals(0, row.activity);
        assertEquals(0, row.coupon);
        assertEquals(expectedLineSum, row.total, "total = 行合计 − 券（会员只在单价里减过一次）");
        assertEquals(expectedLineSum, row.lineSum, "行合计必须与订单头同值（否则巡检必报）");
        assertEquals(memberUnit, jdbc.queryForObject(
                "SELECT unit_price_cents FROM cabinet_order_line WHERE order_id = ?",
                Integer.class, ORDER_ID), "行单价就是会员折后单价");
        assertEquals(row.gross - row.member - row.activity - row.coupon, row.total,
                "恒等式 gross − member − activity − coupon = total");

        // 反例：②口径会把留档再减一次 ⇒ 少收 expectedMemberArchive 分（资损）
        assertTrue(row.total > expectedLineSum - expectedMemberArchive,
                "应付不得再减会员档（②重复扣减必须被拦住）");
    }

    /**
     * 独立对账 SQL（不看 Java，只跑原文）：
     * ① 全表 PAID 单满足恒等式；② 会员档不再制造「折后式」误报（V425 把 V252 的 ②形种子改成 ①形）。
     */
    @Test
    void reconciliationSql_flagsNoMemberRelatedMismatch() {
        bookedMemberDiscount_persistsAndKeepsLedgerIdentity();

        Integer identityViolations = jdbc.queryForObject(
                "SELECT count(*) FROM cabinet_order "
                        + "WHERE status IN ('PAID', 'PARTIAL_REFUNDED') "
                        + "AND gross_amount_cents > 0 "
                        + "AND gross_amount_cents - COALESCE(member_discount_cents, 0) "
                        + "    - COALESCE(activity_discount_cents, 0) "
                        + "    - COALESCE(coupon_discount_cents, 0) <> total_amount_cents",
                Integer.class);
        assertEquals(0, identityViolations, "gross − member − activity − coupon = total 必须逐单成立");

        // 巡检检测式原文口径（DataConsistencyService.checkOrderConsistency 的 HAVING 式）
        Integer payableFromLinesMismatchWithMember = jdbc.queryForObject(
                "SELECT count(*) FROM ("
                        + "  SELECT o.order_id, o.total_amount_cents, "
                        + "         COALESCE(SUM(ol.line_amount_cents), 0) AS line_subtotal, "
                        + "         COALESCE(o.member_discount_cents, 0) AS member_discount, "
                        + "         COALESCE(o.coupon_discount_cents, 0) AS coupon_discount "
                        + "  FROM cabinet_order o LEFT JOIN cabinet_order_line ol ON o.order_id = ol.order_id "
                        + "  WHERE o.status IN ('PAID', 'PARTIAL_REFUNDED') "
                        + "  GROUP BY o.order_id, o.total_amount_cents, o.member_discount_cents, "
                        + "           o.coupon_discount_cents "
                        + "  HAVING o.total_amount_cents <> COALESCE(SUM(ol.line_amount_cents), 0) "
                        + "       - COALESCE(o.coupon_discount_cents, 0) "
                        + ") bad WHERE member_discount > 0",
                Integer.class);
        assertEquals(0, payableFromLinesMismatchWithMember,
                "会员档 > 0 的单不得被折后式判成不一致（旧 ②式会把每单会员折扣都误报）");

        Integer legacySecondDeductionRows = jdbc.queryForObject(
                "SELECT count(*) FROM cabinet_order_line ol JOIN cabinet_order o ON ol.order_id = o.order_id "
                        + "WHERE o.member_discount_cents = 59 AND o.total_amount_cents = 441 "
                        + "AND ol.unit_price_cents = 500",
                Integer.class);
        assertEquals(0, legacySecondDeductionRows,
                "V425 必须把 V252 的 ②形演示种子改成含折扣的 ①形（否则 uat/local 挂一条修不掉的红灯）");
    }

    private LedgerRow readLedger(String orderId) {
        CabinetOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new AssertionError("订单未落库：" + orderId));
        int lineSum = Optional.ofNullable(jdbc.queryForObject(
                "SELECT COALESCE(SUM(line_amount_cents), 0) FROM cabinet_order_line WHERE order_id = ?",
                Integer.class, orderId)).orElse(0);
        return new LedgerRow(order.getGrossAmountCents(), order.getMemberDiscountCents(),
                order.getActivityDiscountCents(), order.getCouponDiscountCents(),
                order.getTotalAmountCents(), lineSum);
    }

    private record LedgerRow(int gross, int member, int activity, int coupon, int total, int lineSum) {}
}
