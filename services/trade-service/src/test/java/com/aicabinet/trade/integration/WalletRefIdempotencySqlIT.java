package com.aicabinet.trade.integration;

import com.aicabinet.trade.service.StaffWalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-1 扩展审（V355/V359/V361，2026-10-16）钉住钱包流水的**库级幂等兜底**。
 *
 * <p>V388 三域物理统一后，三张 {@code *_wallet_ledger} 合并为单表 {@code wallet_ledger}
 * （复合业务键 {@code domain + owner_id}）。库级唯一兜底随之收敛为一条覆盖
 * {@code (domain, owner_id, ref_type, ref_id)} 的 partial 唯一索引 {@code uk_wallet_ledger_ref}。
 * 本 IT 用真库把「幂等路径的 ref 唯一」和「提现生命周期 {@code ref_type='WITHDRAW'} 一 ref 多行
 * 必须继续允许」两条口径钉成硬约束。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class WalletRefIdempotencySqlIT {

    private static final long STAFF = 900001L;
    private static final String STAFF_OWNER = String.valueOf(STAFF);

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
    private StaffWalletService staffWalletService;

    @Test
    @DisplayName("统一流水表必须有覆盖 (domain, owner_id, ref_type, ref_id) 的唯一索引")
    void walletLedgerHasUniqueRefIndex() {
        List<String> hits = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'wallet_ledger' "
                        + "AND indexdef LIKE 'CREATE UNIQUE INDEX%' "
                        + "AND indexdef LIKE '%domain%' AND indexdef LIKE '%owner_id%' "
                        + "AND indexdef LIKE '%ref_type%' AND indexdef LIKE '%ref_id%'",
                String.class);
        assertEquals(1, hits.size(), "wallet_ledger 缺少（或有多个）覆盖 domain/ref 的唯一索引：" + hits);
    }

    @Test
    @DisplayName("同一 refId 的第二笔流水在库级被拒（不依赖应用层查重与分布式锁）")
    void duplicateRefRowIsRejectedAtDbLevel() {
        seedStaffWallet();
        insertStaffLedger("IT_DUP", "ref-dup", -100L);
        assertThrows(DataIntegrityViolationException.class,
                () -> insertStaffLedger("IT_DUP", "ref-dup", -100L),
                "同 (domain, owner_id, ref_type, ref_id) 的第二行竟然插入成功 ⇒ 幂等只活在应用层");
        assertEquals(1, countStaffLedgerByRef("ref-dup"));
    }

    @Test
    @DisplayName("唯一索引是 partial 的：无 ref 的手工调账流水仍可多笔")
    void nullRefRowsAreNotConstrained() {
        seedStaffWallet();
        insertStaffLedger("IT_ADJUST", null, 50L);
        insertStaffLedger("IT_ADJUST", null, 50L);
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM wallet_ledger "
                        + "WHERE domain = 'STAFF' AND owner_id = ? AND ref_id IS NULL",
                Integer.class, STAFF_OWNER));
    }

    @Test
    @DisplayName("ref_type='WITHDRAW' 豁免：提现生命周期天然一 ref 多行（含重试再冻结）")
    void withdrawLifecycleRowsMayRepeatRef() {
        seedStaffWallet();
        // FREEZE → RELEASE → 重试 FREEZE：同 (domain, owner_id, ref_type, ref_id, entry_type) 也会出现第二行
        // （MerchantWithdrawService / LineWithdrawService 提现重试路径），uk_wallet_ledger_ref 因此把 WITHDRAW 排除。
        insertStaffLedgerType("WITHDRAW_FREEZE", "WITHDRAW", "req-life", -100L);
        insertStaffLedgerType("WITHDRAW_RELEASE", "WITHDRAW", "req-life", 100L);
        insertStaffLedgerType("WITHDRAW_FREEZE", "WITHDRAW", "req-life", -100L);
        assertEquals(3, countStaffLedgerByRef("req-life"),
                "WITHDRAW 生命周期行被唯一索引误伤 ⇒ 提现打款/重试会当场 500");
    }

    @Test
    @DisplayName("提现标记重复提交只扣一次（应用层幂等仍成立，与库级兜底同向）")
    void payoutDoubleSubmitChargesOnce() {
        seedStaffWallet();
        assertTrue(staffWalletService.creditIfAbsent(STAFF, 500L, "COMMISSION", "IT_CREDIT", "ref-credit", "IT 入账"));
        assertTrue(staffWalletService.payoutIfAbsent(STAFF, 100L, "WITHDRAW_PAYOUT", "STAFF_WITHDRAW", "req-1", "IT 打款"));
        assertEquals(400L, staffBalance(), "首次入账+出账后余额应为 400");
        // 同一 requestNo 重放（双击/网络重试）：必须返回 false 且不再扣
        assertFalse(staffWalletService.payoutIfAbsent(STAFF, 100L, "WITHDRAW_PAYOUT", "STAFF_WITHDRAW", "req-1", "IT 打款重试"));
        assertEquals(400L, staffBalance(), "重放同一 requestNo 把余额又扣了一次");
        assertFalse(staffWalletService.creditIfAbsent(STAFF, 500L, "COMMISSION", "IT_CREDIT", "ref-credit", "IT 入账重放"));
        assertEquals(400L, staffBalance());
    }

    private void seedStaffWallet() {
        staffWalletService.ensureAccount(STAFF);
    }

    private void insertStaffLedger(String entryType, String refId, long amountCents) {
        insertStaffLedgerType(entryType, "IT_PROBE", refId, amountCents);
    }

    private void insertStaffLedgerType(String entryType, String refType, String refId, long amountCents) {
        jdbc.update("INSERT INTO wallet_ledger (domain, owner_id, entry_type, amount_cents, "
                        + "balance_after, frozen_after, ref_type, ref_id, remark) "
                        + "VALUES ('STAFF', ?, ?, ?, 0, 0, ?, ?, 'negative-test')",
                STAFF_OWNER, entryType, amountCents, refType, refId);
    }

    private long countStaffLedgerByRef(String refId) {
        Long c = jdbc.queryForObject(
                "SELECT count(*) FROM wallet_ledger WHERE domain = 'STAFF' AND ref_id = ?",
                Long.class, refId);
        return c == null ? 0L : c;
    }

    private long staffBalance() {
        Long b = jdbc.queryForObject(
                "SELECT balance_cents FROM wallet_account WHERE domain = 'STAFF' AND owner_id = ?",
                Long.class, STAFF_OWNER);
        return b == null ? 0L : b;
    }
}
