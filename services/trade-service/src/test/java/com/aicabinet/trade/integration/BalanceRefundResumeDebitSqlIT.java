package com.aicabinet.trade.integration;

import com.aicabinet.trade.service.BalanceRefundService;
import org.junit.jupiter.api.Disabled;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FS-20（第四轮审计 2026-10-18）：余额退款「续退补扣」不变量。
 *
 * <p>{@code BalanceRefundService.debitRemainingBalance} 先手动把账户余额减掉未成功额度、
 * 再调用 {@code BalanceLedgerService.change(-cents, ...)} —— 而 change 自身会读同一行
 * （已被手动扣减的值）再减一次。期望：补扣 400 之后余额只减 400。</p>
 */
@Disabled("FS-20 复现钉：修复批 1 落地后删除本注解转为回归测试")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class BalanceRefundResumeDebitSqlIT {

    private static final long USER = 730001L;
    private static final long REQUEST_ID = 730002L;
    private static final String REQUEST_NO = "BRQ-730002";

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
    private BalanceRefundService balanceRefundService;

    @Test
    @DisplayName("续退补扣只应扣一次未成功额度（余额必须等于流水口径）")
    void resumeDebitMustDeductRemainingOnlyOnce() {
        jdbc.update("INSERT INTO user_info (user_id, phone_number, name) VALUES (?, '17300000001', '续退补扣 IT')", USER);
        jdbc.update("INSERT INTO user_account (user_id, balance_cents) VALUES (?, 10000)", USER);
        jdbc.update("INSERT INTO recharge_order (order_id, user_id, amount_cents, channel, status, paid_at, idempotency_key) "
                + "VALUES ('RC-730-A', ?, 600, 'WECHAT', 'PAID', NOW(), 'IT-730-A')", USER);
        jdbc.update("INSERT INTO recharge_order (order_id, user_id, amount_cents, channel, status, paid_at, idempotency_key) "
                + "VALUES ('RC-730-B', ?, 400, 'WECHAT', 'PAID', NOW(), 'IT-730-B')", USER);
        jdbc.update("INSERT INTO balance_refund_request (request_id, request_no, user_id, amount_cents, status) "
                + "VALUES (?, ?, ?, 1000, 'PARTIAL')", REQUEST_ID, REQUEST_NO, USER);
        jdbc.update("INSERT INTO balance_refund_allocation (request_id, recharge_order_id, amount_cents, channel, out_refund_no) "
                + "VALUES (?, 'RC-730-A', 600, 'WECHAT', 'BR730002-S1')", REQUEST_ID);

        balanceRefundService.debitRemainingAndMarkProcessing(REQUEST_ID);

        List<Map<String, Object>> ledger = jdbc.queryForList(
                "SELECT operation_type, amount_cents, balance_before_cents, balance_after_cents "
                        + "FROM payment_operation WHERE idempotency_key = 'BALANCE_REFUND_RESUME:' || ?", REQUEST_NO);
        Integer balance = jdbc.queryForObject(
                "SELECT balance_cents FROM user_account WHERE user_id = ?", Integer.class, USER);
        String state = jdbc.queryForObject(
                "SELECT status FROM balance_refund_request WHERE request_id = ?", String.class, REQUEST_ID);
        System.out.println("[FS-20] 补扣后余额=" + balance + " 流水行=" + ledger + " 申请状态=" + state);

        assertEquals(1, ledger.size(), "续退补扣应且仅应留下一条 RESUME 流水");
        Map<String, Object> row = ledger.get(0);
        assertEquals(400, ((Number) row.get("amount_cents")).intValue(), "流水金额应为未成功额度 400");
        // 不变量：余额 = 起始 10000 − 本次补扣 400；且流水快照尾值必须等于账户余额
        assertEquals(9600, balance.intValue(),
                "余额被重复扣减（手动扣一次 + change 再扣一次）：期望 9600，实得 " + balance);
        assertEquals(balance.intValue(), ((Number) row.get("balance_after_cents")).intValue(),
                "流水 balance_after 与账户余额脱钩 ⇒ 余额 ≠ Σ流水");
        assertEquals("PROCESSING", state);
    }
}
