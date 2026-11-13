package com.aicabinet.trade.integration;

import com.aicabinet.trade.mapper.WalletAccountMapper;
import com.aicabinet.trade.service.MerchantWalletArrearsService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CB-086 真库回归：欠款账龄探针的 XML 必须能在真库执行。
 *
 * <p>为什么必须走真库：单测用 mock 掉 mapper，<b>XML 里写错的动态 SQL 永远抓不到</b>。
 * 2026-11-13 实测踩过：{@code <if>} 被写进 CDATA ⇒ SQL 变成字面量 {@code <if ...>} ⇒
 * {@code syntax error at or near "<"}，名单与 CSV 两个接口直接 500。本 IT 起真 PG + 走完整
 * Flyway 链，用 JdbcTemplate 播账户/账本行，再调 mapper 与 service，钉住：
 * ① 负余额过滤；② 账龄起算=最后一次非负；③ 整本账从未非负回落最早一行；
 * ④ 无流水 ⇒ c2 为 null（不可判，不得当 0 天）；⑤ 按商户收窄。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class WalletArrearsSqlIT {

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

    private static final String M_NEG = "MCH-IT-ARREARS-NEG";
    private static final String M_NEVER_POS = "MCH-IT-ARREARS-NEVERPOS";
    private static final String M_NO_LEDGER = "MCH-IT-ARREARS-NOLEDGER";
    private static final String M_POSITIVE = "MCH-IT-ARREARS-POS";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private WalletAccountMapper accountMapper;
    @Autowired private MerchantWalletArrearsService arrearsService;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM wallet_ledger WHERE owner_id LIKE 'MCH-IT-ARREARS-%'");
        jdbc.update("DELETE FROM wallet_account WHERE owner_id LIKE 'MCH-IT-ARREARS-%'");
        account(M_NEG, -10_000L);
        account(M_NEVER_POS, -5_000L);
        account(M_NO_LEDGER, -3_000L);
        account(M_POSITIVE, 1_000L);
        // 正常段：先 +50000（非负），再 -60000（转负）⇒ 起算点应是「非负那条」的时间
        ledger(M_NEG, "ADJUST", 50_000L, 50_000L, "2026-08-01T10:00:00+08:00");
        ledger(M_NEG, "SPLIT_REVERSE", -60_000L, -10_000L, "2026-08-05T10:00:00+08:00");
        // 整本账从未非负
        ledger(M_NEVER_POS, "ADJUST", -5_000L, -5_000L, "2026-09-01T10:00:00+08:00");
    }

    private void account(String ownerId, long balanceCents) {
        jdbc.update("INSERT INTO wallet_account (domain, owner_id, balance_cents, frozen_cents) "
                + "VALUES ('MERCHANT', ?, ?, 0) ON CONFLICT (domain, owner_id) DO UPDATE SET balance_cents = EXCLUDED.balance_cents",
                ownerId, balanceCents);
    }

    private void ledger(String ownerId, String entryType, long amount, long balanceAfter, String at) {
        jdbc.update("INSERT INTO wallet_ledger (domain, owner_id, entry_type, amount_cents, balance_after, "
                        + "frozen_after, created_at) VALUES ('MERCHANT', ?, ?, ?, ?, 0, ?::timestamptz)",
                ownerId, entryType, amount, balanceAfter, at);
    }

    private Map<String, Object> row(String ownerId, List<Map<String, Object>> rows) {
        return rows.stream().filter(r -> ownerId.equals(String.valueOf(r.get("c0"))))
                .findFirst().orElse(null);
    }

    @Test
    void negativeOnly_andArrearsSinceIsLastNonNegative() {
        List<Map<String, Object>> rows = accountMapper.findNegativeArrears("MERCHANT", null);
        // 只有 3 个负余额账户；正余额不得出现
        assertNull(row(M_POSITIVE, rows), "正余额账户不应出现在欠款探针里");
        Map<String, Object> neg = row(M_NEG, rows);
        assertNotNull(neg, "负余额账户必须命中");
        assertEquals(-10_000L, ((Number) neg.get("c1")).longValue());
        assertNotNull(neg.get("c2"), "有非负历史的账户必须能算出起算点");
        assertTrue(String.valueOf(neg.get("c2")).startsWith("2026-08-01"),
                "起算点应取最后一次 balance_after>=0 的那条：" + neg.get("c2"));
    }

    @Test
    void neverNonNegative_fallsBackToEarliestLedgerRow() {
        Map<String, Object> row = row(M_NEVER_POS, accountMapper.findNegativeArrears("MERCHANT", null));
        assertNotNull(row);
        assertTrue(String.valueOf(row.get("c2")).startsWith("2026-09-01"), String.valueOf(row.get("c2")));
    }

    @Test
    void noLedger_returnsNullSince_notZeroDays() {
        Map<String, Object> row = row(M_NO_LEDGER, accountMapper.findNegativeArrears("MERCHANT", null));
        assertNotNull(row, "无流水的负余额账户仍需出现在名单里（不可判 ≠ 不存在）");
        assertNull(row.get("c2"), "无流水 ⇒ 起算日必须为 null（不可判），不得当作 0 天");
        assertFalse(arrearsService.inArrears(M_NO_LEDGER), "不可判的账户不得被锁柜");
    }

    @Test
    void ownerScopedQuery_filtersToOneMerchant() {
        List<Map<String, Object>> rows = accountMapper.findNegativeArrears("MERCHANT", M_NEG);
        assertEquals(1, rows.size());
        assertEquals(M_NEG, String.valueOf(rows.get(0).get("c0")));
    }

    @Test
    void serviceComputesAgeAgainstBusinessDay() {
        var arrears = arrearsService.negativeAccounts().stream()
                .filter(a -> M_NEG.equals(a.merchantId())).findFirst().orElseThrow();
        long expected = java.time.temporal.ChronoUnit.DAYS.between(
                LocalDate.of(2026, 8, 1), LocalDate.now(com.aicabinet.trade.support.ScheduleZones.ZONE));
        assertEquals(expected, arrears.ageDays(), "账龄按业务日（Asia/Shanghai）自然日计");
        // 2026-08-01 起算到运行时已远超默认锁柜阈值 90 天 ⇒ 必须判定为「欠款中」
        assertTrue(arrearsService.inArrears(M_NEG), "超过默认阈值 90 天必须命中锁柜判据");
    }
}
