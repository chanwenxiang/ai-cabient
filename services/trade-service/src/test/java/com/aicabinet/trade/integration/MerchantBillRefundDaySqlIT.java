package com.aicabinet.trade.integration;

import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MA-271：商户月账单的退款按「退款发生日」归日，不按下单日。
 *
 * <p>改的是 {@code CabinetOrderLineMapper.xml#selectMerchantBillRefundsByDay} 的分桶与过滤口径，
 * 单测里 mapper 是 mock ⇒ 这条 SQL 从没被数据库执行过。本 IT 用真 PostgreSQL 钉三件事：
 * <ol>
 *   <li>3 月下单、4 月退款的订单，退款进 <b>4 月</b>账单（修前被记进 3 月 ⇒ 4 月利润虚高）；</li>
 *   <li>3 月账单<b>不被追溯改写</b>（修前同一笔钱同时出现在两个月的账上，历史月对不上）；</li>
 *   <li>{@code refunded_at} 为空的老数据仍按下单日兜底 —— 不能因为改口径让历史退款整笔消失。</li>
 * </ol>
 *
 * <p>已知精度边界（不是本 IT 能覆盖的）：同一订单多次部分退款时 {@code refunded_cents} 是累计值、
 * {@code refunded_at} 只记最后一次，整笔会归到最近一个月。要精确到笔需要退款流水表，当前无此表。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class MerchantBillRefundDaySqlIT {

    private static final String PREFIX = "IT-REFDAY-";
    private static final String MERCHANT = "MCH-IT-REFDAY";
    private static final String DEVICE = "DEV-IT-REFDAY";
    private static final long USER = 17410000001L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

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
    private CabinetOrderLineMapper orderLineRepository;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM cabinet_order WHERE order_id LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM shopping_session WHERE session_id LIKE ?", PREFIX + "%");
        jdbc.update("INSERT INTO user_info (user_id, phone_number) VALUES (?, ?) ON CONFLICT (user_id) DO NOTHING",
                USER, "17410000001");
        jdbc.update("INSERT INTO device_info (device_id) VALUES (?) ON CONFLICT (device_id) DO NOTHING", DEVICE);
    }

    @Test
    @DisplayName("跨月退款：进退款发生月，且不再出现在下单月")
    void refundAcrossMonth_belongsToRefundMonth() {
        // 3 月下单、4 月退款 500（渠道原路退）
        seedOrder("A", "2026-03-20T10:00", "2026-04-10T10:00", 500, "WECHAT");
        // 4 月下单、4 月退款 300（余额退）
        seedOrder("B", "2026-04-05T10:00", "2026-04-06T10:00", 300, "BALANCE");

        Totals april = refunds("2026-04", "2026-05");
        assertEquals(800L, april.total(), "4 月退款合计应包含 3 月下单、4 月退的那 500（修前只有 300）");
        assertEquals(500L, april.instant(), "c1 非余额桶应按笔归入 4 月");
        assertEquals(300L, april.balance(), "c2 余额桶应按笔归入 4 月");
        assertEquals(2L, april.count(), "两笔退款都发生在 4 月");

        Totals march = refunds("2026-03", "2026-04");
        assertEquals(0L, march.total(), "3 月账单被 4 月的退款追溯改写了 —— 已经发给商户的历史月会对不上");
        assertEquals(0L, march.count());
    }

    @Test
    @DisplayName("未退款订单不进退款聚合（refunded_cents=0）")
    void orderWithoutRefund_isNotListed() {
        seedOrder("C", "2026-04-05T10:00", null, 0, "WECHAT");

        Totals april = refunds("2026-04", "2026-05");
        assertEquals(0L, april.total());
        assertEquals(0L, april.count());
    }

    @Test
    @DisplayName("refunded_at 为空的老数据按下单日兜底，不能整笔消失")
    void legacyRowWithoutRefundedAt_fallsBackToOrderDay() {
        // 只有累计退款列、没有退款时点（refunded_at 是后加列）
        seedOrder("D", "2026-04-05T10:00", null, 200, "WECHAT");

        Totals april = refunds("2026-04", "2026-05");
        assertEquals(200L, april.total(), "refunded_at 为空时必须回退下单日，否则历史退款一分钱都统计不到");
        assertEquals(1L, april.count());
    }

    @Test
    @DisplayName("FAILED 订单的退款不计入（与修前一致）")
    void failedOrder_isExcluded() {
        seedOrder("E", "2026-04-05T10:00", "2026-04-06T10:00", 400, "WECHAT");
        jdbc.update("UPDATE cabinet_order SET status = 'FAILED' WHERE order_id = ?", PREFIX + "E");

        Totals april = refunds("2026-04", "2026-05");
        assertEquals(0L, april.total());
    }

    private record Totals(long instant, long balance, long count) {
        long total() {
            return instant + balance;
        }
    }

    /** 从 [fromMonth 初, toMonth 初) 的月窗口取聚合。 */
    private Totals refunds(String fromMonth, String toMonth) {
        Instant start = monthStart(fromMonth);
        Instant end = monthStart(toMonth);
        long instant = 0;
        long balance = 0;
        long count = 0;
        for (Object[] row : orderLineRepository.merchantBillRefundsByDay(
                List.of(MERCHANT), start, end, null)) {
            instant += ((Number) row[1]).longValue();
            balance += ((Number) row[2]).longValue();
            count += ((Number) row[3]).longValue();
        }
        return new Totals(instant, balance, count);
    }

    private static Instant monthStart(String month) {
        return LocalDate.parse(month + "-01").atStartOfDay(ZONE).toInstant();
    }

    private static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(ZONE).toInstant();
    }

    /**
     * @param refundedAt null 表示「只有累计退款列、没有退款时点」的老数据形态
     */
    private void seedOrder(String suffix, String createdAt, String refundedAt, int refundedCents, String channel) {
        String id = PREFIX + suffix;
        jdbc.update("INSERT INTO shopping_session (session_id, user_id, device_id, state) "
                + "VALUES (?, ?, ?, 'COMPLETED')", PREFIX + "S-" + suffix, USER, DEVICE);
        jdbc.update("INSERT INTO cabinet_order (order_id, session_id, user_id, device_id, merchant_id, "
                        + "total_amount_cents, status, pay_channel, created_at, refunded_cents, refunded_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'PAID', ?, ?, ?, ?)",
                id, PREFIX + "S-" + suffix, USER, DEVICE, MERCHANT, Math.max(refundedCents, 100),
                channel, Timestamp.from(at(createdAt)),
                refundedCents, refundedAt == null ? null : Timestamp.from(at(refundedAt)));
    }
}
