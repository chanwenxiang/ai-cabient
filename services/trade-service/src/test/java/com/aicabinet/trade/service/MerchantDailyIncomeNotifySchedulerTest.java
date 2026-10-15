package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.OrderRevenueSplit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 日结通知聚合口径（CB-020 补充，2026-10-14 用户拍板）：
 * 冲正单不计、null/空商户不计、负数按 0、**0.00 商户不发**（与老系统全量推送有意不同）。
 */
class MerchantDailyIncomeNotifySchedulerTest {

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

    private MerchantDailyIncomeNotifyScheduler newSchedulerForOverdue(
            com.aicabinet.trade.mapper.MerchantFeeLedgerMapper feeLedgerRepository,
            com.aicabinet.trade.mapper.DeviceInfoMapper deviceRepository,
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
                mock(com.aicabinet.trade.service.MerchantWalletService.class),
                configService,
                salesLockService);
    }

    private com.aicabinet.trade.domain.MerchantFeeLedger pendingFee(long cents) {
        com.aicabinet.trade.domain.MerchantFeeLedger r = new com.aicabinet.trade.domain.MerchantFeeLedger();
        r.setMerchantId("M1");
        r.setFeeDate(java.time.LocalDate.now().minusDays(1));
        r.setFeeType("REN");
        r.setAmountCents(cents);
        r.setStatus("PENDING");
        return r;
    }

    private com.aicabinet.trade.domain.DeviceInfo rentalDevice(boolean locked, String lockReason) {
        com.aicabinet.trade.domain.DeviceInfo d = new com.aicabinet.trade.domain.DeviceInfo();
        d.setDeviceId("D1");
        d.setMerchantId("M1");
        d.setCoopMode("RENTAL");
        d.setRentCents(10_000L); // 月租 100 元
        d.setSalesLocked(locked);
        d.setSalesLockReason(lockReason);
        return d;
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
        var config = mock(com.aicabinet.trade.service.SystemConfigService.class);
        when(config.getValue(anyString(), anyString())).thenReturn("1");
        var svc = newSchedulerForOverdue(feeLedger, deviceRepo, salesLock, notify, notifyLog,
                mock(com.aicabinet.trade.mapper.MerchantMapper.class), config);

        svc.checkOverdueAndRemind(java.time.LocalDate.now().minusDays(1), java.util.Set.of("M1"));

        verify(notify, never()).notifyMerchant(anyString(), anyString(), any(), anyString(), anyString());
        verify(salesLock, never()).applySalesLock(any(), any(), eq(true), anyString(), eq(true));
    }

    @Test
    void feeDebtOverThreshold_locksDevices() {
        // 未结清费用 150 元 ≥ 月租 100 元 × lockMonths=1 ⇒ 锁柜（费用口径债，与余额无关）
        var feeLedger = mock(com.aicabinet.trade.mapper.MerchantFeeLedgerMapper.class);
        when(feeLedger.selectList(any())).thenReturn(List.of(pendingFee(9_000L), pendingFee(6_000L)));
        var deviceRepo = mock(com.aicabinet.trade.mapper.DeviceInfoMapper.class);
        when(deviceRepo.selectList(any())).thenReturn(List.of(rentalDevice(false, null)));
        var salesLock = mock(com.aicabinet.trade.service.DeviceSalesLockService.class);
        var notify = mock(com.aicabinet.trade.service.NotificationService.class);
        var notifyLog = mock(com.aicabinet.trade.mapper.NotificationLogMapper.class);
        when(notifyLog.selectCount(any())).thenReturn(0L);
        var config = mock(com.aicabinet.trade.service.SystemConfigService.class);
        when(config.getValue(anyString(), anyString())).thenReturn("1");
        var merchantRepo = mock(com.aicabinet.trade.mapper.MerchantMapper.class);
        var svc = newSchedulerForOverdue(feeLedger, deviceRepo, salesLock, notify, notifyLog,
                merchantRepo, config);

        svc.checkOverdueAndRemind(java.time.LocalDate.now().minusDays(1), java.util.Set.of("M1"));

        verify(salesLock).applySalesLock(any(), any(), eq(true), contains("费用欠缴锁柜"), eq(true));
        verify(notify).notifyMerchant(eq("M1"), eq("merchant_fee_reminder"), any(),
                eq("FEE_REMINDER"), anyString());
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
        var config = mock(com.aicabinet.trade.service.SystemConfigService.class);
        when(config.getValue(anyString(), anyString())).thenReturn("1");
        var svc = newSchedulerForOverdue(feeLedger, deviceRepo, salesLock, notify, notifyLog,
                mock(com.aicabinet.trade.mapper.MerchantMapper.class), config);

        svc.checkOverdueAndRemind(java.time.LocalDate.now().minusDays(1), java.util.Set.of("M1"));

        // 只解 FEE_OVERDUE 前缀一台
        verify(salesLock, times(1)).applySalesLock(any(), any(), eq(false), anyString(), eq(true));
    }
}
