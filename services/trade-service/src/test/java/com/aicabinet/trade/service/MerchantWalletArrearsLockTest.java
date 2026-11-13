package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.MerchantFeeLedger;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantFeeLedgerMapper;
import com.aicabinet.trade.service.MerchantWalletArrearsService.Arrears;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CB-086 第二段：钱包欠款锁柜（2026-11-13 用户拍板「做」）。
 *
 * <p>钉住四条安全判据：① 只锁未锁设备（不接管费用欠费锁）；② 复机只解本前缀、且要求
 * 不再欠款 + 无未结费用；③ 阈值 0=关闭；④ 费用已清但钱包仍欠款时**不得**代 unlock。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MerchantWalletArrearsLockTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    private static final String M1 = "M1";

    @Mock private com.aicabinet.trade.mapper.MerchantMapper merchantRepository;
    @Mock private com.aicabinet.trade.mapper.OrderRevenueSplitMapper splitRepository;
    @Mock private NotificationService notificationService;
    @Mock private com.aicabinet.trade.mapper.NotificationLogMapper notificationLogRepository;
    @Mock private ScheduledTaskService taskService;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private MerchantFeeLedgerMapper feeLedgerRepository;
    @Mock private MerchantWalletService walletService;
    @Mock private SystemConfigService configService;
    @Mock private DeviceSalesLockService salesLockService;
    @Mock private MerchantWalletArrearsService walletArrearsService;

    private MerchantDailyIncomeNotifyScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new MerchantDailyIncomeNotifyScheduler(
                merchantRepository, splitRepository, notificationService, notificationLogRepository,
                taskService, deviceRepository, feeLedgerRepository, walletService, configService,
                salesLockService, walletArrearsService);
        when(feeLedgerRepository.selectList(any())).thenReturn(List.of());
    }

    private static DeviceInfo device(String id, boolean locked, String reason) {
        DeviceInfo d = new DeviceInfo();
        d.setDeviceId(id);
        d.setMerchantId(M1);
        d.setSalesLocked(locked);
        d.setSalesLockReason(reason);
        return d;
    }

    private void devices(DeviceInfo... ds) {
        when(deviceRepository.selectList(any())).thenReturn(List.of(ds));
    }

    private void lockEnabled(int days, Arrears... hits) {
        when(walletArrearsService.lockThresholdDays()).thenReturn(days);
        when(walletArrearsService.overThreshold(eq((long) days), any())).thenReturn(List.of(hits));
    }

    @Test
    void locksOnlyUnlockedDevices_withDedicatedReason() {
        lockEnabled(90, new Arrears(M1, 5_000L, 91, TODAY.minusDays(91)));
        devices(device("D1", false, null), device("D2", true, "费用欠缴锁柜：欠费 10.00 元"));
        when(walletArrearsService.inArrears(M1)).thenReturn(true);

        scheduler.enforceWalletArrears(TODAY);

        verify(salesLockService).applySalesLock(eq(null),
                org.mockito.ArgumentMatchers.argThat(d -> d != null && "D1".equals(d.getDeviceId())),
                eq(true), contains(MerchantDailyIncomeNotifyScheduler.WALLET_ARREARS_LOCK_PREFIX), eq(true));
        verify(salesLockService, never()).applySalesLock(any(),
                org.mockito.ArgumentMatchers.argThat(d -> d != null && "D2".equals(d.getDeviceId())),
                anyBoolean(), any(), anyBoolean());
    }

    @Test
    void lockDisabled_doesNothing() {
        lockEnabled(0);
        devices(device("D1", false, null));
        scheduler.enforceWalletArrears(TODAY);
        verify(walletArrearsService, never()).overThreshold(org.mockito.ArgumentMatchers.anyLong(), any());
        verify(salesLockService, never()).applySalesLock(any(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    void unlocksOwnPrefixWhenClearedAndNoFeeDebt() {
        lockEnabled(90);
        String reason = MerchantDailyIncomeNotifyScheduler.WALLET_ARREARS_LOCK_PREFIX + "：钱包欠款 50.00 元，连续 100 天";
        devices(device("D1", true, reason));
        when(walletArrearsService.inArrears(M1)).thenReturn(false);

        scheduler.enforceWalletArrears(TODAY);

        verify(salesLockService).applySalesLock(eq(null), org.mockito.ArgumentMatchers.argThat(
                d -> d != null && "D1".equals(d.getDeviceId())), eq(false), anyString(), eq(true));
    }

    @Test
    void keepsLockedWhenFeeDebtRemains() {
        lockEnabled(90);
        String reason = MerchantDailyIncomeNotifyScheduler.WALLET_ARREARS_LOCK_PREFIX + "：钱包欠款 50.00 元";
        devices(device("D1", true, reason));
        when(walletArrearsService.inArrears(M1)).thenReturn(false);
        MerchantFeeLedger fee = new MerchantFeeLedger();
        fee.setMerchantId(M1);
        fee.setAmountCents(1_000L);
        fee.setStatus("PENDING");
        when(feeLedgerRepository.selectList(any())).thenReturn(List.of(fee));

        scheduler.enforceWalletArrears(TODAY);

        verify(salesLockService, never()).applySalesLock(any(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    void feeClearedButWalletStillInArrears_keepsFeeLockedDeviceLocked() {
        lockEnabled(90);
        devices(device("D1", true, MerchantDailyIncomeNotifyScheduler.FEE_OVERDUE_LOCK_PREFIX + "：欠费 10.00 元"));
        when(walletArrearsService.inArrears(M1)).thenReturn(true);

        // 费用债已清（无 PENDING 行）⇒ 走 unlockFeeOverdue；但钱包仍欠款 ⇒ 不得代解锁
        scheduler.checkOverdueAndRemind(TODAY.minusDays(1), Set.of(M1));

        verify(salesLockService, never()).applySalesLock(any(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    void feeClearedAndWalletCleared_unlocksFeeDevice() {
        lockEnabled(90);
        devices(device("D1", true, MerchantDailyIncomeNotifyScheduler.FEE_OVERDUE_LOCK_PREFIX + "：欠费 10.00 元"));
        when(walletArrearsService.inArrears(M1)).thenReturn(false);

        scheduler.checkOverdueAndRemind(TODAY.minusDays(1), Set.of(M1));

        verify(salesLockService).applySalesLock(eq(null), any(), eq(false), anyString(), eq(true));
    }
}
