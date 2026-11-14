package com.aicabinet.trade.service;

import com.aicabinet.trade.support.ScheduleZones;
import java.time.LocalDate;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CB-086 第二段（欠款锁柜）的**真库**回归：只锁未锁柜机 / 结清自动复机 / 费用债挡住复机，
 * 三条判据在 Mockito 版 {@code MerchantWalletArrearsLockTest} 里是「verify 调用发生过」，
 * 本 IT 钉的是**库里真的变成什么样**。
 *
 * <p>为什么必须有真库这一份（2026-11-14）：mock 版把 {@code DeviceSalesLockService} 整个替掉，
 * 于是这条腿上有三处只有真库能证伪的环节——
 * ① 锁柜原因要**真的落库**，下一轮复机才能靠 {@code LIKE '钱包欠款锁柜%'} 把自己锁的设备找回来
 *    （MyBatis-Plus {@code updateById} 跳过 null 字段，解锁靠 {@code clearSalesLockReason} 显式清列）；
 * ② {@code applySalesLock(null, …)} 的审计写 {@code admin_audit_log.operator_id}，
 *    该列 NOT NULL + FK(user_info)：null 没归一成 0 就会整事务回滚，而调用点把异常 catch 成 WARN ⇒
 *    表现为「任务绿、柜机一台没锁」（2026-11-06 实测过的同族缺陷）；
 * ③ 锁柜判据来自 {@code findNegativeArrears} 的 XML（CDATA 坑见 lessons 431）。
 *
 * <p>边端下发在测试环境没有 device-service：把 {@code aicabinet.device-service.url} 指到 1 端口，
 * 连接立即被拒并回退 LOCAL 指令号（源码里这条路径本身有 try/catch，不能让它挂住 IT）。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class WalletArrearsLockSqlIT {

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
        // 边端不可达 ⇒ 立刻失败并走 LOCAL 兜底，不占 IT 时间
        registry.add("aicabinet.device-service.url", () -> "http://127.0.0.1:1");
    }

    /** 欠款满账龄（无 sys_config 行 ⇒ 走默认阈值 90 天）。 */
    private static final String M_ARREARS = "MCH-IT-LCK-1";
    /** 余额为负但账本无流水 ⇒ 账龄不可判，一律 fail-open 不锁。 */
    private static final String M_NOLEDGER = "MCH-IT-LCK-2";

    private static final String D_FREE = "DEV-IT-LCK-FREE";
    private static final String D_FEE = "DEV-IT-LCK-FEE";
    private static final String D_NOLEDGER = "DEV-IT-LCK-NOLD";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MerchantDailyIncomeNotifyScheduler scheduler;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM merchant_fee_ledger WHERE merchant_id IN (?, ?)", M_ARREARS, M_NOLEDGER);
        jdbc.update("DELETE FROM admin_audit_log WHERE target_id LIKE 'DEV-IT-LCK-%'");
        jdbc.update("DELETE FROM device_info WHERE device_id LIKE 'DEV-IT-LCK-%'");
        jdbc.update("DELETE FROM wallet_ledger WHERE owner_id IN (?, ?)", M_ARREARS, M_NOLEDGER);
        jdbc.update("DELETE FROM wallet_account WHERE owner_id IN (?, ?)", M_ARREARS, M_NOLEDGER);
        jdbc.update("DELETE FROM merchant WHERE merchant_id IN (?, ?)", M_ARREARS, M_NOLEDGER);

        merchant(M_ARREARS, "欠款锁柜 IT 商户甲");
        merchant(M_NOLEDGER, "欠款锁柜 IT 商户乙");
        device(D_FREE, M_ARREARS, false, null);
        device(D_FEE, M_ARREARS, true,
                MerchantDailyIncomeNotifyScheduler.FEE_OVERDUE_LOCK_PREFIX + "：欠费 10.00 元");
        device(D_NOLEDGER, M_NOLEDGER, false, null);

        // 起算点 = 最后一次 balance_after >= 0 ⇒ 账龄 120 天 > 默认 90 天阈值
        String lastSolvent = LocalDate.now(ScheduleZones.ZONE).minusDays(120) + "T10:00:00+08:00";
        account(M_ARREARS, -10_000L);
        ledger(M_ARREARS, "ADJUST", 50_000L, 50_000L, lastSolvent);
        ledger(M_ARREARS, "SPLIT_REVERSE", -60_000L, -10_000L, lastSolvent);
        // 负余额 + 零流水：名单里必须出现（不可判 ≠ 不存在），但绝不锁柜
        account(M_NOLEDGER, -3_000L);
    }

    private void merchant(String id, String name) {
        // uk_merchant_name 唯一 ⇒ 两个夹具商户不能同名
        jdbc.update("INSERT INTO merchant (merchant_id, merchant_name, remark) VALUES (?, ?, ?)",
                id, name, "WalletArrearsLockSqlIT");
    }

    private void account(String ownerId, long balanceCents) {
        jdbc.update("INSERT INTO wallet_account (domain, owner_id, balance_cents, frozen_cents) "
                + "VALUES ('MERCHANT', ?, ?, 0)", ownerId, balanceCents);
    }

    private void ledger(String ownerId, String entryType, long amount, long balanceAfter, String at) {
        jdbc.update("INSERT INTO wallet_ledger (domain, owner_id, entry_type, amount_cents, balance_after, "
                        + "frozen_after, created_at) VALUES ('MERCHANT', ?, ?, ?, ?, 0, ?::timestamptz)",
                ownerId, entryType, amount, balanceAfter, at);
    }

    private void device(String id, String merchantId, boolean locked, String reason) {
        // uk_device_name 唯一 ⇒ 柜名跟着 device_id 走
        jdbc.update("INSERT INTO device_info (device_id, device_name, device_type, online_status, "
                        + "merchant_id, sales_locked, sales_lock_reason) "
                        + "VALUES (?, ?, 'AI_CABINET_V1', 'OFFLINE', ?, ?, ?)",
                id, "欠款锁柜 IT 柜 " + id, merchantId, locked, reason);
    }

    private Boolean locked(String deviceId) {
        return jdbc.queryForObject("SELECT sales_locked FROM device_info WHERE device_id = ?",
                Boolean.class, deviceId);
    }

    private String reason(String deviceId) {
        return jdbc.queryForObject("SELECT sales_lock_reason FROM device_info WHERE device_id = ?",
                String.class, deviceId);
    }

    private long auditCount(String action, String deviceId) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_log "
                        + "WHERE action = ? AND target_id = ?", Long.class, action, deviceId);
    }

    /** 结清：余额回正并追加一条非负流水 ⇒ 账龄段结束、inArrears=false。 */
    private void settleArrears() {
        jdbc.update("UPDATE wallet_account SET balance_cents = 50000 WHERE owner_id = ?", M_ARREARS);
        jdbc.update("INSERT INTO wallet_ledger (domain, owner_id, entry_type, amount_cents, balance_after, "
                        + "frozen_after, created_at) VALUES ('MERCHANT', ?, 'ADJUST', 60000, 50000, 0, NOW())",
                M_ARREARS);
    }

    private String prefix() {
        return MerchantDailyIncomeNotifyScheduler.WALLET_ARREARS_LOCK_PREFIX;
    }

    @Test
    @DisplayName("欠款满账龄：只锁未锁的柜机，原因与系统操作人审计真的落库")
    void locksUnlockedDeviceAndPersistsReasonWithSystemOperator() {
        scheduler.enforceWalletArrears(LocalDate.now(ScheduleZones.ZONE));

        assertTrue(locked(D_FREE), "欠款满账龄的商户，未锁柜机必须被锁");
        assertNotNull(reason(D_FREE), "锁柜原因必须真的落库——下一轮复机靠它找回本前缀的设备");
        assertTrue(reason(D_FREE).startsWith(prefix()), "原因前缀失真则复机 LIKE 找不到：" + reason(D_FREE));
        assertNull(jdbc.queryForObject("SELECT sales_unlocked_at FROM device_info WHERE device_id = ?",
                        String.class, D_FREE),
                "上锁要清空 sales_unlocked_at（updateById 跳过 null ⇒ 靠 clear 语句）");

        // 费用欠费锁归费用链所有：本轮既不接管也不改写
        assertTrue(reason(D_FEE).startsWith(MerchantDailyIncomeNotifyScheduler.FEE_OVERDUE_LOCK_PREFIX),
                "已因费用被锁的柜机不得被钱包链改写原因");

        // 负余额但账本无流水 ⇒ 账龄不可判 ⇒ fail-open 不锁
        assertFalse(Boolean.TRUE.equals(locked(D_NOLEDGER)), "账龄不可判的商户不得被锁柜");

        // operator_id NOT NULL + FK：null 必须归一成 0（系统），否则整事务回滚、表现为「一台没锁」
        assertEquals(1L, auditCount("DEVICE_LOCK", D_FREE), "锁柜必须留下 DEVICE_LOCK 审计");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE action = 'DEVICE_LOCK' AND target_id = ? "
                        + "AND operator_id = 0", Long.class, D_FREE),
                "审计操作人应归一为系统账号 0");
    }

    @Test
    @DisplayName("第二轮不重复上锁（幂等：只锁未锁设备）")
    void secondRoundDoesNotRelockOrDuplicateAudit() {
        LocalDate today = LocalDate.now(ScheduleZones.ZONE);
        scheduler.enforceWalletArrears(today);
        scheduler.enforceWalletArrears(today);

        assertTrue(locked(D_FREE));
        assertEquals(1L, auditCount("DEVICE_LOCK", D_FREE),
                "重复轮次不得再上一次锁、再写一条审计");
    }

    @Test
    @DisplayName("结清后自动复机：库里解锁并清掉停售原因")
    void clearedArrearsUnlocksAndClearsReasonInDb() {
        scheduler.enforceWalletArrears(LocalDate.now(ScheduleZones.ZONE));
        assertTrue(locked(D_FREE), "前置：先被锁上");

        settleArrears();
        scheduler.enforceWalletArrears(LocalDate.now(ScheduleZones.ZONE));

        assertFalse(locked(D_FREE), "欠款结清后必须自动复机");
        assertNull(reason(D_FREE),
                "解锁必须显式清 sales_lock_reason（MP updateById 跳过 null ⇒ 残留旧原因会让下轮 LIKE 误认领）");
        assertNotNull(jdbc.queryForObject("SELECT sales_unlocked_at FROM device_info WHERE device_id = ?",
                String.class, D_FREE), "复机要留下解锁时间");
        assertEquals(1L, auditCount("DEVICE_UNLOCK", D_FREE), "复机必须留下 DEVICE_UNLOCK 审计");
    }

    @Test
    @DisplayName("钱包结清但仍有未结费用：复机被费用债挡住，柜子保持锁定")
    void feeDebtBlocksAutoUnlock() {
        scheduler.enforceWalletArrears(LocalDate.now(ScheduleZones.ZONE));
        settleArrears();
        jdbc.update("INSERT INTO merchant_fee_ledger (merchant_id, fee_date, fee_type, amount_cents, status) "
                + "VALUES (?, ?, 'DATA', 1000, 'PENDING')",
                M_ARREARS, LocalDate.now(ScheduleZones.ZONE).minusDays(3));

        scheduler.enforceWalletArrears(LocalDate.now(ScheduleZones.ZONE));

        assertTrue(locked(D_FREE), "费用未结清时不得代理解锁（两条链各管自己的归属）");
        assertTrue(reason(D_FREE).startsWith(prefix()), "仍由钱包前缀持有该锁");
        assertEquals(0L, auditCount("DEVICE_UNLOCK", D_FREE));
    }
}
