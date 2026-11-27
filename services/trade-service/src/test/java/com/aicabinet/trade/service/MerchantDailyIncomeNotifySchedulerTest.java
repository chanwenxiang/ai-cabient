package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.OrderRevenueSplit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 日结通知 + 周期费用日扣口径（CB-020 补充、CB-041、2026-10-16 F-2/F-3/F-4 拍板）：
 * 冲正单不计、null/空商户不计、负数按 0、**0.00 商户不发**（与老系统全量推送有意不同）；
 * 流量费=分/天且不分租赁/买断、0/NULL 不扣不写行、扣款成功才写 SETTLED 行、
 * 欠费锁柜按「账龄满 lock_days」判定、漏扣靠 {@code rerunDailyFees(日期)} 人工重跑。
 */
class MerchantDailyIncomeNotifySchedulerTest {

    /** F-5 用例：捕获发给商户的模板参数（amount/fee/net）。 */
    private final java.util.concurrent.atomic.AtomicReference<Map<String, String>> lastParams =
            new java.util.concurrent.atomic.AtomicReference<>();

    private OrderRevenueSplit split(String merchantId, String status, long merchantCents) {
        OrderRevenueSplit s = new OrderRevenueSplit();
        s.setMerchantId(merchantId);
        s.setStatus(status);
        s.setMerchantCents(merchantCents);
        return s;
    }

    @Test
    void aggregatesPerMerchantAndDropsZeroIncome() {
        Map<String, Long> m = MerchantDailyIncomeNotifyScheduler.aggregateIncome(List.of(
                split("M1", "ACCRUED", 500),
                split("M1", "ACCRUED", 260),
                split("M2", "ACCRUED", 0),          // 当日 0.00：不发（拍板）
                split("M3", "LEDGER_ONLY", 100)));
        assertEquals(760L, m.get("M1"));
        assertEquals(100L, m.get("M3"));
        assertFalse(m.containsKey("M2"), "0.00 商户不应出现在发送集合");
    }

    @Test
    void excludesReversedAndBlankMerchants() {
        Map<String, Long> m = MerchantDailyIncomeNotifyScheduler.aggregateIncome(List.of(
                split("M1", "VOIDED", 500),          // 全额退款冲正：不计
                split("M1", "REVERSED", 300),        // 分账回退冲正：不计
                split("", "ACCRUED", 100),           // 空商户：不计
                split(null, "ACCRUED", 100)));
        assertTrue(m.isEmpty());
    }

    @Test
    void negativeMerchantCentsClampedToZero() {
        Map<String, Long> m = MerchantDailyIncomeNotifyScheduler.aggregateIncome(List.of(
                split("M1", "ACCRUED", -50)));
        assertFalse(m.containsKey("M1"), "负数按 0 处理后仍为 0.00：不发");
    }

    // ---------- 审计 T3（2026-10-15）：欠费判据=费用口径债（pendingFeeTotal），非钱包余额 ----------

    private MerchantDailyIncomeNotifyScheduler newScheduler(
            com.aicabinet.trade.mapper.MerchantFeeLedgerMapper feeLedgerRepository,
            com.aicabinet.trade.mapper.DeviceInfoMapper deviceRepository,
            com.aicabinet.trade.service.MerchantWalletService walletService,
            com.aicabinet.trade.service.DeviceSalesLockService salesLockService,
            com.aicabinet.trade.service.NotificationService notificationService,
            com.aicabinet.trade.mapper.NotificationLogMapper notificationLogRepository,
            com.aicabinet.trade.mapper.MerchantMapper merchantRepository,
            com.aicabinet.trade.service.SystemConfigService configService) {
        return new MerchantDailyIncomeNotifyScheduler(
                merchantRepository,
                mock(com.aicabinet.trade.mapper.OrderRevenueSplitMapper.class),
                notificationService,
                notificationLogRepository,
                mock(com.aicabinet.trade.service.ScheduledTaskService.class),
                deviceRepository,
                feeLedgerRepository,
                walletService,
                configService,
                salesLockService,
                walletArrearsService(),
                mock(com.aicabinet.trade.service.MerchantDailyReportNotifier.class));
    }

    /** CB-086：欠款探针与本类用例无关，默认返回空串（不污染日结摘要断言）。 */
    private com.aicabinet.trade.service.MerchantWalletArrearsService walletArrearsService() {
        var svc = mock(com.aicabinet.trade.service.MerchantWalletArrearsService.class);
        when(svc.dailySummary(any())).thenReturn("");
        return svc;
    }

    private com.aicabinet.trade.service.SystemConfigService configWithLockDays(String lockDays) {
        var config = mock(com.aicabinet.trade.service.SystemConfigService.class);
        when(config.getValue(anyString(), anyString())).thenReturn(lockDays);
        return config;
    }

    private com.aicabinet.trade.domain.MerchantFeeLedger pendingFee(long cents) {
        return pendingFee(cents, java.time.LocalDate.now(com.aicabinet.trade.support.ScheduleZones.ZONE)
                .minusDays(1));
    }

    private com.aicabinet.trade.domain.MerchantFeeLedger pendingFee(long cents, java.time.LocalDate feeDate) {
        com.aicabinet.trade.domain.MerchantFeeLedger r = new com.aicabinet.trade.domain.MerchantFeeLedger();
        r.setMerchantId("M1");
        r.setFeeDate(feeDate);
        r.setFeeType("REN");
        r.setAmountCents(cents);
        r.setStatus("PENDING");
        return r;
    }

    private com.aicabinet.trade.domain.DeviceInfo device(String coopMode, Long rentCents, Long dataFeeCents) {
        com.aicabinet.trade.domain.DeviceInfo d = new com.aicabinet.trade.domain.DeviceInfo();
        d.setDeviceId("D1");
        d.setMerchantId("M1");
        d.setCoopMode(coopMode);
        d.setRentCents(rentCents);
        d.setDataFeeCents(dataFeeCents);
        return d;
    }

    private com.aicabinet.trade.domain.DeviceInfo rentalDevice(boolean locked, String lockReason) {
        com.aicabinet.trade.domain.DeviceInfo d = device("RENTAL", 10_000L, null); // 月租 100 元
        d.setSalesLocked(locked);
        d.setSalesLockReason(lockReason);
        return d;
    }

    /** 台账写入捕获：返回 mock 上被 insert 的行。 */
    private List<com.aicabinet.trade.domain.MerchantFeeLedger> captureInserts(
            com.aicabinet.trade.mapper.MerchantFeeLedgerMapper feeLedger) {
        List<com.aicabinet.trade.domain.MerchantFeeLedger> rows = new java.util.ArrayList<>();
        when(feeLedger.insert(any())).thenAnswer(inv -> {
            rows.add(inv.getArgument(0));
            return 1;
        });
        return rows;
    }

    @Test
    void negativeBalanceButNoFeeDebt_neitherRemindsNorLocks() {
        // 钱包被押金/佣金冲正打负不是费用欠缴：PENDING 台账=0 ⇒ 不催缴不锁柜
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        when(feeLedger.selectList(any())).thenReturn(List.of());
        var deviceRepo = mock(com.aicabinet.trade.mapper.DeviceInfoMapper.class);
        when(deviceRepo.selectList(any())).thenReturn(List.of(rentalDevice(false, null)));
        var salesLock = mock(com.aicabinet.trade.service.DeviceSalesLockService.class);
        var notify = mock(com.aicabinet.trade.service.NotificationService.class);
        var notifyLog = mock(com.aicabinet.trade.mapper.NotificationLogMapper.class);
        when(notifyLog.selectCount(any())).thenReturn(0L);
        var svc = newScheduler(feeLedger, deviceRepo, mock(com.aicabinet.trade.service.MerchantWalletService.class),
                salesLock, notify, notifyLog, mock(com.aicabinet.trade.mapper.MerchantMapper.class),
                configWithLockDays("7"));

        svc.checkOverdueAndRemind(java.time.LocalDate.now().minusDays(1), java.util.Set.of("M1"));

        verify(notify, never()).notifyMerchant(anyString(), anyString(), any(), anyString(), anyString());
        verify(salesLock, never()).applySalesLock(any(), any(), eq(true), anyString(), eq(true));
    }

    @Test
    void feeDebtYoungerThanLockDays_remindsButDoesNotLock() {
        // 2026-10-16 拍板：锁柜判据改「账龄满 lock_days（默认 7）」——欠费第 6 天只催缴不锁柜
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        when(feeLedger.selectList(any())).thenReturn(List.of(pendingFee(9_000L), pendingFee(6_000L)));
        var deviceRepo = mock(com.aicabinet.trade.mapper.DeviceInfoMapper.class);
        when(deviceRepo.selectList(any())).thenReturn(List.of(rentalDevice(false, null)));
        var salesLock = mock(com.aicabinet.trade.service.DeviceSalesLockService.class);
        var notify = mock(com.aicabinet.trade.service.NotificationService.class);
        var notifyLog = mock(com.aicabinet.trade.mapper.NotificationLogMapper.class);
        when(notifyLog.selectCount(any())).thenReturn(0L);
        var svc = newScheduler(feeLedger, deviceRepo, mock(com.aicabinet.trade.service.MerchantWalletService.class),
                salesLock, notify, notifyLog, mock(com.aicabinet.trade.mapper.MerchantMapper.class),
                configWithLockDays("7"));
        var today = java.time.LocalDate.now(com.aicabinet.trade.support.ScheduleZones.ZONE);
        when(feeLedger.selectList(any())).thenReturn(List.of(
                pendingFee(9_000L, today.minusDays(6)), pendingFee(6_000L, today.minusDays(2))));

        svc.checkOverdueAndRemind(today.minusDays(1), java.util.Set.of("M1"));

        verify(notify).notifyMerchant(eq("M1"), eq("merchant_fee_reminder"), any(),
                eq("FEE_REMINDER"), anyString());
        verify(salesLock, never()).applySalesLock(any(), any(), eq(true), anyString(), eq(true));
    }

    @Test
    void feeDebtAgedLockDays_locksDevices() {
        // 欠费账龄满 7 天 ⇒ 锁柜（阿里云开放搜索「欠费满 7 个自然日停止服务」同款），锁因带欠费金额与账龄
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        var deviceRepo = mock(com.aicabinet.trade.mapper.DeviceInfoMapper.class);
        when(deviceRepo.selectList(any())).thenReturn(List.of(rentalDevice(false, null)));
        var salesLock = mock(com.aicabinet.trade.service.DeviceSalesLockService.class);
        var notify = mock(com.aicabinet.trade.service.NotificationService.class);
        var notifyLog = mock(com.aicabinet.trade.mapper.NotificationLogMapper.class);
        when(notifyLog.selectCount(any())).thenReturn(0L);
        var svc = newScheduler(feeLedger, deviceRepo, mock(com.aicabinet.trade.service.MerchantWalletService.class),
                salesLock, notify, notifyLog, mock(com.aicabinet.trade.mapper.MerchantMapper.class),
                configWithLockDays("7"));
        var today = java.time.LocalDate.now(com.aicabinet.trade.support.ScheduleZones.ZONE);
        when(feeLedger.selectList(any())).thenReturn(List.of(
                pendingFee(9_000L, today.minusDays(7)), pendingFee(6_000L, today.minusDays(3))));

        svc.checkOverdueAndRemind(today.minusDays(1), java.util.Set.of("M1"));

        verify(salesLock).applySalesLock(any(), any(), eq(true), contains("费用欠缴锁柜"), eq(true));
    }

    @Test
    void feeDebtCleared_unlocksOnlyOverduePrefix() {
        // 费用全部结清 ⇒ 只解锁「费用欠缴锁柜」前缀的柜机（其它原因锁不碰）
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        when(feeLedger.selectList(any())).thenReturn(List.of());
        var deviceRepo = mock(com.aicabinet.trade.mapper.DeviceInfoMapper.class);
        when(deviceRepo.selectList(any())).thenReturn(java.util.List.of(
                rentalDevice(true, "费用欠缴锁柜：欠费 100.00 元"),
                rentalDevice(true, "人工锁机")));
        var salesLock = mock(com.aicabinet.trade.service.DeviceSalesLockService.class);
        var notify = mock(com.aicabinet.trade.service.NotificationService.class);
        var notifyLog = mock(com.aicabinet.trade.mapper.NotificationLogMapper.class);
        when(notifyLog.selectCount(any())).thenReturn(0L);
        var svc = newScheduler(feeLedger, deviceRepo, mock(com.aicabinet.trade.service.MerchantWalletService.class),
                salesLock, notify, notifyLog, mock(com.aicabinet.trade.mapper.MerchantMapper.class),
                configWithLockDays("7"));

        svc.checkOverdueAndRemind(java.time.LocalDate.now().minusDays(1), java.util.Set.of("M1"));

        // 只解 FEE_OVERDUE 前缀一台
        verify(salesLock, times(1)).applySalesLock(any(), any(), eq(false), anyString(), eq(true));
    }

    // ---------- F-3（2026-10-16 拍板）：流量费=分/天、全部柜机照收、0/空=不收 ----------

    private com.aicabinet.trade.service.MerchantWalletService walletThatCharges() {
        var wallet = mock(com.aicabinet.trade.service.MerchantWalletService.class);
        when(wallet.debitIfAbsentAllowNegative(anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(true);
        return wallet;
    }

    private com.aicabinet.trade.mapper.DeviceInfoMapper deviceRepoWith(
            com.aicabinet.trade.domain.DeviceInfo... devices) {
        var repo = mock(com.aicabinet.trade.mapper.DeviceInfoMapper.class);
        when(repo.selectList(any())).thenReturn(List.of(devices));
        return repo;
    }

    @Test
    void purchasedDeviceIsChargedDailyDataFee() {
        // 老系统 L3：流量费不分租赁/买断，柜机所有人的每台柜都收；单位=分/天，**不除当月天数**
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        var wallet = walletThatCharges();
        var svc = newScheduler(feeLedger, deviceRepoWith(device("PURCHASE", null, 100L)), wallet,
                mock(com.aicabinet.trade.service.DeviceSalesLockService.class),
                mock(com.aicabinet.trade.service.NotificationService.class),
                mock(com.aicabinet.trade.mapper.NotificationLogMapper.class),
                mock(com.aicabinet.trade.mapper.MerchantMapper.class), configWithLockDays("7"));

        long charged = svc.chargeDailyPeriodFees("M1", java.time.LocalDate.of(2026, 10, 15));

        assertEquals(100L, charged, "买断柜的流量费必须照收（分/天，不摊销）");
        verify(wallet).debitIfAbsentAllowNegative(eq("M1"), eq(100L), eq("DAILY_FEE"),
                eq("SETTLEMENT_FEE"), eq("2026-10-15"), anyString());
    }

    @Test
    void dataFeeDoesNotDependOnMonthLength() {
        // 28 天的 2 月与 31 天的 10 月，同一台柜日扣流量费必须相等（老系统逐台每日扣）
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        var svc = newScheduler(feeLedger, deviceRepoWith(device("RENTAL", null, 100L)), walletThatCharges(),
                mock(com.aicabinet.trade.service.DeviceSalesLockService.class),
                mock(com.aicabinet.trade.service.NotificationService.class),
                mock(com.aicabinet.trade.mapper.NotificationLogMapper.class),
                mock(com.aicabinet.trade.mapper.MerchantMapper.class), configWithLockDays("7"));

        assertEquals(100L, svc.chargeDailyPeriodFees("M1", java.time.LocalDate.of(2026, 2, 15)));
        assertEquals(100L, svc.chargeDailyPeriodFees("M1", java.time.LocalDate.of(2026, 10, 15)));
    }

    @Test
    void zeroAndNullDataFeeChargeNothingAndWriteNothing() {
        // 用户 2026-10-16：「这些费用可以为 0——就是不收或者没有」。
        // 0=不收、NULL=没配卡（老系统 :114 对 NULL 拆箱 NPE 整批崩，是我们必须避开的缺陷），
        // 两者都不得扣钱、不得写台账、更不得进欠费判据。
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        var wallet = mock(com.aicabinet.trade.service.MerchantWalletService.class);
        var svc = newScheduler(feeLedger, deviceRepoWith(device("RENTAL", null, 0L),
                device("PURCHASE", null, null)), wallet,
                mock(com.aicabinet.trade.service.DeviceSalesLockService.class),
                mock(com.aicabinet.trade.service.NotificationService.class),
                mock(com.aicabinet.trade.mapper.NotificationLogMapper.class),
                mock(com.aicabinet.trade.mapper.MerchantMapper.class), configWithLockDays("7"));

        assertEquals(0L, svc.chargeDailyPeriodFees("M1", java.time.LocalDate.of(2026, 10, 15)));

        verify(wallet, never()).debitIfAbsentAllowNegative(anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyString());
        verify(feeLedger, never()).insert(any());
    }

    @Test
    void successfulDebitWritesSettledRowsNotPending() {
        // 2026-10-16 拍板 F-2=A：扣款成功 ⇒ 台账行直接 SETTLED（行即收据，老系统 L4/L5 + 微信分账语义）
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        var rows = captureInserts(feeLedger);
        var svc = newScheduler(feeLedger, deviceRepoWith(device("RENTAL", 10_000L, 100L)), walletThatCharges(),
                mock(com.aicabinet.trade.service.DeviceSalesLockService.class),
                mock(com.aicabinet.trade.service.NotificationService.class),
                mock(com.aicabinet.trade.mapper.NotificationLogMapper.class),
                mock(com.aicabinet.trade.mapper.MerchantMapper.class), configWithLockDays("7"));

        long charged = svc.chargeDailyPeriodFees("M1", java.time.LocalDate.of(2026, 10, 15));

        // MA-286 余数分摊：10000=31×322 余 18 ⇒ 当月第 15 天（≤18）收 323 分；第 19 天起收 322；
        // 全月合计恰 10000（修前逐日 floor 永久吞掉 18 分）
        assertEquals(423L, charged, "月租 10000/31 天余 18，第 15 天=322+1 分摊 + 流量费 100");
        assertEquals(2, rows.size());
        // B4：DATA 逐台行带 deviceId；REN 保持商户级（device_id=null）
        org.junit.jupiter.api.Assertions.assertEquals("D1",
                rows.stream().filter(r -> "DATA".equals(r.getFeeType())).findFirst().orElseThrow().getDeviceId(),
                "DATA 台账行必须带 device 维度（B4/V486）");
        org.junit.jupiter.api.Assertions.assertNull(
                rows.stream().filter(r -> "REN".equals(r.getFeeType())).findFirst().orElseThrow().getDeviceId(),
                "REN 台账行保持商户级聚合（device_id=null）");
        for (var row : rows) {
            assertEquals("SETTLED", row.getStatus(), "扣款成功的费用行不得留 PENDING（否则同一笔钱既收了又算欠费）");
            org.junit.jupiter.api.Assertions.assertNotNull(row.getSettledAt());
        }
    }

    @Test
    void debitExceptionLeavesNoLedgerRow() {
        // 老系统 L5「先扣到钱才写行」：扣款抛异常 ⇒ 当日既不留行也不留欠费（不会自动补，运营按日期重跑）
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        var rows = captureInserts(feeLedger);
        var wallet = mock(com.aicabinet.trade.service.MerchantWalletService.class);
        when(wallet.debitIfAbsentAllowNegative(anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString())).thenThrow(new IllegalStateException("钱包锁获取失败"));
        var svc = newScheduler(feeLedger, deviceRepoWith(device("RENTAL", 10_000L, 100L)), wallet,
                mock(com.aicabinet.trade.service.DeviceSalesLockService.class),
                mock(com.aicabinet.trade.service.NotificationService.class),
                mock(com.aicabinet.trade.mapper.NotificationLogMapper.class),
                mock(com.aicabinet.trade.mapper.MerchantMapper.class), configWithLockDays("7"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> svc.chargeDailyPeriodFees("M1", java.time.LocalDate.of(2026, 10, 15)));

        assertTrue(rows.isEmpty(), "扣款失败却写了费用行 ⇒ 「钱没扣、账先挂上」，正是 F-2 现状缺陷");
    }

    @Test
    void rerunForDateChargesOnceAndChecksOverdue() {
        // F-4=A：运营台「按日期重跑日扣」入口=chargeAndCheckAllMerchants(该日, 空集)，
        // 商户集合按设备表枚举（不依赖昨日有收入商户）
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        when(feeLedger.selectList(any())).thenReturn(List.of());
        var wallet = walletThatCharges();
        var svc = newScheduler(feeLedger, deviceRepoWith(device("RENTAL", null, 100L)), wallet,
                mock(com.aicabinet.trade.service.DeviceSalesLockService.class),
                mock(com.aicabinet.trade.service.NotificationService.class),
                mock(com.aicabinet.trade.mapper.NotificationLogMapper.class),
                mock(com.aicabinet.trade.mapper.MerchantMapper.class), configWithLockDays("7"));

        svc.rerunDailyFees(java.time.LocalDate.of(2026, 10, 10));

        verify(wallet).debitIfAbsentAllowNegative(eq("M1"), eq(100L), eq("DAILY_FEE"),
                eq("SETTLEMENT_FEE"), eq("2026-10-10"), anyString());
    }

    // ---------- F-5（2026-10-17）：通知费用取钱包实扣流水 + 扣费失败在结果里显形 ----------

    private com.aicabinet.trade.service.NotificationService capturedNotify() {
        var notify = mock(com.aicabinet.trade.service.NotificationService.class);
        org.mockito.Mockito.doAnswer(inv -> {
            lastParams.set(inv.getArgument(2));
            return null;
        }).when(notify).notifyMerchant(anyString(), anyString(), any(), anyString(), anyString());
        return notify;
    }

    private MerchantDailyIncomeNotifyScheduler newNotifyScheduler(
            com.aicabinet.trade.mapper.OrderRevenueSplitMapper splitRepo,
            com.aicabinet.trade.mapper.MerchantFeeLedgerMapper feeLedger,
            com.aicabinet.trade.mapper.DeviceInfoMapper deviceRepo,
            com.aicabinet.trade.service.MerchantWalletService wallet,
            com.aicabinet.trade.service.NotificationService notify,
            com.aicabinet.trade.mapper.NotificationLogMapper notifyLog) {
        return new MerchantDailyIncomeNotifyScheduler(
                mock(com.aicabinet.trade.mapper.MerchantMapper.class), splitRepo, notify, notifyLog,
                mock(com.aicabinet.trade.service.ScheduledTaskService.class), deviceRepo, feeLedger,
                wallet, configWithLockDays("7"),
                mock(com.aicabinet.trade.service.DeviceSalesLockService.class),
                walletArrearsService(),
                mock(com.aicabinet.trade.service.MerchantDailyReportNotifier.class));
    }

    @Test
    void notifyShowsWalletDebitedAmountNotRecomputedFee() {
        // 名下柜机今日重算出来该扣 100 分，但钱包当日流水实扣 250 分（例如运营先按旧配置重跑过）。
        // 通知必须显示 250 —— 商户余额是被 250 减掉的，展示重算值 100 就是「通知与账不一致」。
        var splitRepo = mock(com.aicabinet.trade.mapper.OrderRevenueSplitMapper.class);
        when(splitRepo.selectList(any())).thenReturn(List.of(split("M1", "ACCRUED", 10_000L)));
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        when(feeLedger.selectList(any())).thenReturn(List.of());
        var wallet = walletThatCharges();
        when(wallet.debitedCentsByRef("M1", "SETTLEMENT_FEE", "2026-10-15")).thenReturn(250L);
        var notify = capturedNotify();
        var notifyLog = mock(com.aicabinet.trade.mapper.NotificationLogMapper.class);
        when(notifyLog.selectCount(any())).thenReturn(0L);
        var svc = newNotifyScheduler(splitRepo, feeLedger, deviceRepoWith(device("PURCHASE", null, 100L)),
                wallet, notify, notifyLog);

        var outcome = svc.sendDailyIncomeNotify(java.time.LocalDate.of(2026, 10, 15));

        assertEquals(1, outcome.sent());
        assertEquals(0, outcome.feeChargeFailed());
        assertEquals("2.50", lastParams.get().get("fee"), "费用栏必须取钱包实扣流水");
        assertEquals("97.50", lastParams.get().get("net"));
        assertEquals("100.00", lastParams.get().get("amount"));
    }

    @Test
    void feeChargeFailureIsCountedAndFeeShowsActualZero() {
        // 扣款抛异常（钱没扣、行没写）：通知仍照发，但 fee 取流水=0，且失败数冒泡给运营台执行记录——
        // 不许再出现「商户看到 0.00 费用、没人知道那是漏扣」的静默态。
        var splitRepo = mock(com.aicabinet.trade.mapper.OrderRevenueSplitMapper.class);
        when(splitRepo.selectList(any())).thenReturn(List.of(split("M1", "ACCRUED", 10_000L)));
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        when(feeLedger.selectList(any())).thenReturn(List.of());
        var wallet = mock(com.aicabinet.trade.service.MerchantWalletService.class);
        when(wallet.debitIfAbsentAllowNegative(anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString())).thenThrow(new IllegalStateException("钱包锁获取失败"));
        when(wallet.debitedCentsByRef(anyString(), anyString(), anyString())).thenReturn(0L);
        var notify = capturedNotify();
        var notifyLog = mock(com.aicabinet.trade.mapper.NotificationLogMapper.class);
        when(notifyLog.selectCount(any())).thenReturn(0L);
        var svc = newNotifyScheduler(splitRepo, feeLedger, deviceRepoWith(device("PURCHASE", null, 100L)),
                wallet, notify, notifyLog);

        var outcome = svc.sendDailyIncomeNotify(java.time.LocalDate.of(2026, 10, 15));

        assertEquals(1, outcome.sent(), "单商户扣费失败不得阻断通知");
        assertEquals(1, outcome.feeChargeFailed(), "漏扣必须显形（任务汇总里带失败家数）");
        assertEquals("0.00", lastParams.get().get("fee"), "钱没扣到就如实显示 0");
        assertEquals("100.00", lastParams.get().get("net"));
    }

    @Test
    void alreadyDebitedDayReportsZeroNewChargeButLedgerRowStillWritten() {
        // 幂等跳过（debitIfAbsent 返回 false=该日流水已存在，例如运营先重跑过）
        // ⇒ 「本次实扣」必须 0，否则收入商户在全量循环里会被数两遍；费用行照写（行是收据）。
        var wallet = mock(com.aicabinet.trade.service.MerchantWalletService.class);
        when(wallet.debitIfAbsentAllowNegative(anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(false);
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        var rows = captureInserts(feeLedger);
        var svc = newScheduler(feeLedger, deviceRepoWith(device("PURCHASE", null, 100L)), wallet,
                mock(com.aicabinet.trade.service.DeviceSalesLockService.class),
                mock(com.aicabinet.trade.service.NotificationService.class),
                mock(com.aicabinet.trade.mapper.NotificationLogMapper.class),
                mock(com.aicabinet.trade.mapper.MerchantMapper.class), configWithLockDays("7"));

        assertEquals(0L, svc.chargeDailyPeriodFees("M1", java.time.LocalDate.of(2026, 10, 15)),
                "幂等跳过不该再算一次「本次实扣」");
        assertEquals(1, rows.size(), "跳过扣款仍要留当日费用行，台账才与钱包流水对上");
        assertEquals("DATA", rows.get(0).getFeeType());
    }

    // ---------- MA-377：日报要带「卖了多少」而不只是「到账多少」 ----------

    @Test
    void aggregateSales_countsOrdersIncludingZeroRevenueRows() {
        // 0 元分账行也是真实成交的一单：漏算它客单价会虚高（1901.23 / 292 ≠ / 293）
        Map<String, MerchantDailyIncomeNotifyScheduler.DailySales> m =
                MerchantDailyIncomeNotifyScheduler.aggregateSales(List.of(
                        split("M1", "ACCRUED", 500),
                        split("M1", "ACCRUED", 0),
                        split("M1", "ACCRUED", 260),
                        split("M1", "VOIDED", 900),      // 冲正：金额与笔数都不算
                        split("M2", "ACCRUED", 0)));      // 全额 0：不进结果集（0.00 不发）
        assertEquals(760L, m.get("M1").salesCents());
        assertEquals(3, m.get("M1").orderCount(), "含 0 元行共 3 笔，冲正单不计");
        assertFalse(m.containsKey("M2"));
    }

    @Test
    void dailyInAppMessageCarriesOrdersAndAvg_andDoesNotPushWechatAtMidnight() {
        // 00:20 只做「扣费 + 站内信」：站内信是静默入库，微信是真弹窗 ⇒ 凌晨不许推微信
        var splitRepo = mock(com.aicabinet.trade.mapper.OrderRevenueSplitMapper.class);
        when(splitRepo.selectList(any())).thenReturn(List.of(
                split("M1", "ACCRUED", 150_000L),
                split("M1", "ACCRUED", 40_123L),
                split("M1", "ACCRUED", 0L)));
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        when(feeLedger.selectList(any())).thenReturn(List.of());
        var wallet = mock(com.aicabinet.trade.service.MerchantWalletService.class);
        when(wallet.debitedCentsByRef(anyString(), anyString(), anyString())).thenReturn(0L);
        var notify = capturedNotify();
        var notifyLog = mock(com.aicabinet.trade.mapper.NotificationLogMapper.class);
        when(notifyLog.selectCount(any())).thenReturn(0L);
        var dailyReport = mock(com.aicabinet.trade.service.MerchantDailyReportNotifier.class);
        var svc = schedulerWith(splitRepo, feeLedger, wallet, notify, notifyLog, dailyReport);

        var outcome = svc.sendDailyIncomeNotify(java.time.LocalDate.of(2026, 10, 15));

        assertEquals(1, outcome.sent());
        assertEquals("3", lastParams.get().get("orders"), "站内信文案要带笔数（V499）");
        assertEquals("633.74", lastParams.get().get("avg"), "客单价两位小数");
        verify(dailyReport, never()).send(any());
    }

    @Test
    void morningReportPushesWechatWithSameNumbers() {
        // 07:30 独立任务：只推微信，数字与站内信同源（同一份 aggregateSales 产物）
        var splitRepo = mock(com.aicabinet.trade.mapper.OrderRevenueSplitMapper.class);
        when(splitRepo.selectList(any())).thenReturn(List.of(
                split("M1", "ACCRUED", 150_000L),
                split("M1", "ACCRUED", 40_123L),
                split("M1", "ACCRUED", 0L)));
        var dailyReport = mock(com.aicabinet.trade.service.MerchantDailyReportNotifier.class);
        java.util.concurrent.atomic.AtomicReference<
                com.aicabinet.trade.service.MerchantDailyReportNotifier.MerchantDailyReport> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(dailyReport.send(any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return new com.aicabinet.trade.service.MerchantDailyReportNotifier.DeliveryResult(1, 0, 0);
        });
        var svc = schedulerWith(splitRepo,
                mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class),
                mock(com.aicabinet.trade.service.MerchantWalletService.class),
                mock(com.aicabinet.trade.service.NotificationService.class),
                mock(com.aicabinet.trade.mapper.NotificationLogMapper.class), dailyReport);

        var result = svc.sendWechatReportsOnly(java.time.LocalDate.of(2026, 10, 15));

        assertEquals(1, result.sent(), "送达人数要进运营台执行记录");
        assertEquals(190_123L, captured.get().salesCents());
        assertEquals(3, captured.get().orderCount());
        assertEquals(63_374L, captured.get().avgCents(), "客单价四舍五入到分（1901.23/3）");
        assertEquals("2026-10-15", captured.get().bizId(), "与站内信同幂等键（日期）");
        assertEquals("2026年10月15日", captured.get().period());
    }

    @Test
    void morningReportDeliveryFailureMarksScheduledTaskFailed() {
        var splitRepo = mock(com.aicabinet.trade.mapper.OrderRevenueSplitMapper.class);
        when(splitRepo.selectList(any())).thenReturn(List.of(split("M1", "ACCRUED", 150_000L)));
        var dailyReport = mock(com.aicabinet.trade.service.MerchantDailyReportNotifier.class);
        when(dailyReport.send(any())).thenReturn(
                new com.aicabinet.trade.service.MerchantDailyReportNotifier.DeliveryResult(0, 1, 0));
        var taskService = mock(com.aicabinet.trade.service.ScheduledTaskService.class);
        when(taskService.tryBegin("merchant-daily-report", 600)).thenReturn(true);
        var svc = new MerchantDailyIncomeNotifyScheduler(
                mock(com.aicabinet.trade.mapper.MerchantMapper.class), splitRepo,
                mock(com.aicabinet.trade.service.NotificationService.class),
                mock(com.aicabinet.trade.mapper.NotificationLogMapper.class), taskService,
                deviceRepoWith(device("PURCHASE", null, 0L)),
                mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class),
                mock(com.aicabinet.trade.service.MerchantWalletService.class), configWithLockDays("7"),
                mock(com.aicabinet.trade.service.DeviceSalesLockService.class),
                walletArrearsService(), dailyReport);

        svc.notifyYesterdayReport();

        verify(taskService).finish(eq("merchant-daily-report"), eq("FAILED"),
                org.mockito.ArgumentMatchers.contains("失败 1 人"), org.mockito.ArgumentMatchers.anyLong());
    }

    // ---------- 2026-11-20 改判：有柜机的商户每天都要收到，0 元也收到 ----------

    @Test
    void zeroSalesMerchantWithCabinetStillGetsZeroReport() {
        // 生产长期 0 单：若坚持「有成交才发」，商户永远收不到任何东西，分不清
        // 「今天确实没生意」和「通知坏了」。故有柜机的商户零成交也要发一条 0.00。
        var splitRepo = mock(com.aicabinet.trade.mapper.OrderRevenueSplitMapper.class);
        when(splitRepo.selectList(any())).thenReturn(List.of());
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        when(feeLedger.selectList(any())).thenReturn(List.of());
        var wallet = mock(com.aicabinet.trade.service.MerchantWalletService.class);
        when(wallet.debitedCentsByRef(anyString(), anyString(), anyString())).thenReturn(0L);
        var notify = capturedNotify();
        var notifyLog = mock(com.aicabinet.trade.mapper.NotificationLogMapper.class);
        when(notifyLog.selectCount(any())).thenReturn(0L);
        var dailyReport = mock(com.aicabinet.trade.service.MerchantDailyReportNotifier.class);
        var svc = schedulerWith(splitRepo, feeLedger, wallet, notify, notifyLog, dailyReport);

        var outcome = svc.sendDailyIncomeNotify(java.time.LocalDate.of(2026, 10, 15));

        assertEquals(1, outcome.sent(), "名下有柜机 = 每天必发（含零成交）");
        assertEquals("0.00", lastParams.get().get("amount"));
        assertEquals("0", lastParams.get().get("orders"));
        assertEquals("0.00", lastParams.get().get("avg"), "0 笔时客单价回落 0，不得除零");
        verify(dailyReport, never()).send(any());
    }

    /**
     * P2③（2026-11-21 用户复核）：日报只发给「在运营柜」的商户——INBOUND（入库未投放）与
     * RETIRED（已退役）不算，否则只剩这两类柜的商户每天收 0 元纯噪音。
     * 注意费用口径仍是全表，退役柜欠费照催（另一条测试钉）。
     */
    @Test
    void retiredAndInboundCabinetsDoNotTriggerZeroReport() {
        var splitRepo = mock(com.aicabinet.trade.mapper.OrderRevenueSplitMapper.class);
        when(splitRepo.selectList(any())).thenReturn(List.of());
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        when(feeLedger.selectList(any())).thenReturn(List.of());
        var notify = mock(com.aicabinet.trade.service.NotificationService.class);
        var dailyReport = mock(com.aicabinet.trade.service.MerchantDailyReportNotifier.class);

        var retired = device("PURCHASE", null, 0L);
        retired.setLifecycleStatus("RETIRED");
        var inbound = device("PURCHASE", null, 0L);
        inbound.setLifecycleStatus("INBOUND");
        var svc = new MerchantDailyIncomeNotifyScheduler(
                mock(com.aicabinet.trade.mapper.MerchantMapper.class), splitRepo, notify,
                mock(com.aicabinet.trade.mapper.NotificationLogMapper.class),
                mock(com.aicabinet.trade.service.ScheduledTaskService.class),
                deviceRepoWith(retired, inbound), feeLedger,
                mock(com.aicabinet.trade.service.MerchantWalletService.class), configWithLockDays("7"),
                mock(com.aicabinet.trade.service.DeviceSalesLockService.class),
                walletArrearsService(), dailyReport);

        var outcome = svc.sendDailyIncomeNotify(java.time.LocalDate.of(2026, 10, 15));

        assertEquals(0, outcome.sent(), "只剩退役/入库柜 ⇒ 不发日报（费用口径不受影响）");
        verify(dailyReport, never()).send(any());
    }

    /** 在运营柜（含 IDLE/RETURNING）仍照发 0 元日报，不能因排除退役柜误伤。 */
    @Test
    void operatingCabinetsStillTriggerZeroReport() {
        var splitRepo = mock(com.aicabinet.trade.mapper.OrderRevenueSplitMapper.class);
        when(splitRepo.selectList(any())).thenReturn(List.of());
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        when(feeLedger.selectList(any())).thenReturn(List.of());
        var dailyReport = mock(com.aicabinet.trade.service.MerchantDailyReportNotifier.class);

        var idle = device("PURCHASE", null, 0L);
        idle.setLifecycleStatus("IDLE");
        var returning = device("PURCHASE", null, 0L);
        returning.setLifecycleStatus("RETURNING");
        var svc = new MerchantDailyIncomeNotifyScheduler(
                mock(com.aicabinet.trade.mapper.MerchantMapper.class), splitRepo,
                mock(com.aicabinet.trade.service.NotificationService.class),
                mock(com.aicabinet.trade.mapper.NotificationLogMapper.class),
                mock(com.aicabinet.trade.service.ScheduledTaskService.class),
                deviceRepoWith(idle, returning), feeLedger,
                mock(com.aicabinet.trade.service.MerchantWalletService.class), configWithLockDays("7"),
                mock(com.aicabinet.trade.service.DeviceSalesLockService.class),
                walletArrearsService(), dailyReport);

        var outcome = svc.sendDailyIncomeNotify(java.time.LocalDate.of(2026, 10, 15));

        assertEquals(1, outcome.sent(), "IDLE/RETURNING 属在运营，去重后一个商户一条");
        verify(dailyReport, never()).send(any()); // 00:20 站内信，不打微信
    }

    @Test
    void zeroSalesMerchantWithoutCabinetGetsNothing() {
        // 边界：没柜的商户收 0 元日报纯噪音——扩集合只按设备表枚举
        var splitRepo = mock(com.aicabinet.trade.mapper.OrderRevenueSplitMapper.class);
        when(splitRepo.selectList(any())).thenReturn(List.of());
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        when(feeLedger.selectList(any())).thenReturn(List.of());
        var notify = mock(com.aicabinet.trade.service.NotificationService.class);
        var dailyReport = mock(com.aicabinet.trade.service.MerchantDailyReportNotifier.class);
        var svc = new MerchantDailyIncomeNotifyScheduler(
                mock(com.aicabinet.trade.mapper.MerchantMapper.class), splitRepo, notify,
                mock(com.aicabinet.trade.mapper.NotificationLogMapper.class),
                mock(com.aicabinet.trade.service.ScheduledTaskService.class),
                deviceRepoWith(), feeLedger,
                mock(com.aicabinet.trade.service.MerchantWalletService.class), configWithLockDays("7"),
                mock(com.aicabinet.trade.service.DeviceSalesLockService.class),
                walletArrearsService(), dailyReport);

        var outcome = svc.sendDailyIncomeNotify(java.time.LocalDate.of(2026, 10, 15));

        assertEquals(0, outcome.sent());
        verify(notify, never()).notifyMerchant(anyString(), anyString(), any(), anyString(), anyString());
        verify(dailyReport, never()).send(any());
    }

    @Test
    void morningWechatReportCoversZeroSalesMerchantWithCabinet() {
        // 07:30 的微信侧必须与 00:20 的站内信同集合，否则「站内信有、微信没有」又是一笔对不上
        var splitRepo = mock(com.aicabinet.trade.mapper.OrderRevenueSplitMapper.class);
        when(splitRepo.selectList(any())).thenReturn(List.of());
        var dailyReport = mock(com.aicabinet.trade.service.MerchantDailyReportNotifier.class);
        java.util.concurrent.atomic.AtomicReference<
                com.aicabinet.trade.service.MerchantDailyReportNotifier.MerchantDailyReport> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(dailyReport.send(any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return new com.aicabinet.trade.service.MerchantDailyReportNotifier.DeliveryResult(1, 0, 0);
        });
        var svc = schedulerWith(splitRepo,
                mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class),
                mock(com.aicabinet.trade.service.MerchantWalletService.class),
                mock(com.aicabinet.trade.service.NotificationService.class),
                mock(com.aicabinet.trade.mapper.NotificationLogMapper.class), dailyReport);

        var result = svc.sendWechatReportsOnly(java.time.LocalDate.of(2026, 10, 15));

        assertEquals(1, result.sent());
        assertEquals(0L, captured.get().salesCents());
        assertEquals(0, captured.get().orderCount());
        assertEquals(0L, captured.get().avgCents());
    }

    private MerchantDailyIncomeNotifyScheduler schedulerWith(
            com.aicabinet.trade.mapper.OrderRevenueSplitMapper splitRepo,
            com.aicabinet.trade.mapper.MerchantFeeLedgerMapper feeLedger,
            com.aicabinet.trade.service.MerchantWalletService wallet,
            com.aicabinet.trade.service.NotificationService notify,
            com.aicabinet.trade.mapper.NotificationLogMapper notifyLog,
            com.aicabinet.trade.service.MerchantDailyReportNotifier dailyReport) {
        return new MerchantDailyIncomeNotifyScheduler(
                mock(com.aicabinet.trade.mapper.MerchantMapper.class), splitRepo, notify, notifyLog,
                mock(com.aicabinet.trade.service.ScheduledTaskService.class),
                deviceRepoWith(device("PURCHASE", null, 0L)), feeLedger, wallet, configWithLockDays("7"),
                mock(com.aicabinet.trade.service.DeviceSalesLockService.class),
                walletArrearsService(), dailyReport);
    }
}
