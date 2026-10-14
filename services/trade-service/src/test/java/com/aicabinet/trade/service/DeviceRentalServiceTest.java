package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceRentalBill;
import com.aicabinet.trade.domain.DeviceRentalDeposit;
import com.aicabinet.trade.domain.DeviceRentalDepositEvent;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceRentalBillMapper;
import com.aicabinet.trade.mapper.DeviceRentalDepositEventMapper;
import com.aicabinet.trade.mapper.DeviceRentalDepositMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设备租赁闭环单测（CB-034，含铁律负向：欠租必须锁柜、结清解锁只碰欠租原因的锁）。
 */
class DeviceRentalServiceTest {

    private DeviceRentalDepositMapper depositMapper;
    private DeviceRentalDepositEventMapper depositEventMapper;
    private DeviceRentalBillMapper billMapper;
    private DeviceInfoMapper deviceMapper;
    private PermissionService permissionService;
    private AdminAuditService auditService;
    private SystemConfigService systemConfigService;
    private DeviceSalesLockService salesLockService;
    private FeeBillMonthResolver monthResolver;
    private DistributedLockService distributedLockService;
    private MerchantWalletService merchantWalletService;
    private DeviceRentalService service;

    @BeforeEach
    void setUp() {
        depositMapper = mock(DeviceRentalDepositMapper.class);
        depositEventMapper = mock(DeviceRentalDepositEventMapper.class);
        billMapper = mock(DeviceRentalBillMapper.class);
        deviceMapper = mock(DeviceInfoMapper.class);
        permissionService = mock(PermissionService.class);
        auditService = mock(AdminAuditService.class);
        systemConfigService = mock(SystemConfigService.class);
        salesLockService = mock(DeviceSalesLockService.class);
        monthResolver = mock(FeeBillMonthResolver.class);
        distributedLockService = mock(DistributedLockService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-12T00:20:00Z"), ZoneId.of("Asia/Shanghai"));
        merchantWalletService = mock(MerchantWalletService.class);
        service = new DeviceRentalService(depositMapper, depositEventMapper, billMapper, deviceMapper,
                permissionService, auditService, systemConfigService, salesLockService, monthResolver,
                distributedLockService, merchantWalletService, clock);
        when(monthResolver.resolve(any())).thenAnswer(inv -> {
            String m = inv.getArgument(0);
            return m == null || m.isBlank() ? "2026-10" : m;
        });
        when(monthResolver.clampPage(any())).thenAnswer(inv -> inv.getArgument(0) == null ? 1 : inv.getArgument(0));
        when(monthResolver.clampPageSize(any())).thenAnswer(inv -> inv.getArgument(0) == null ? 20 : inv.getArgument(0));
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(systemConfigService.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(15);
        when(depositMapper.insert(any(DeviceRentalDeposit.class))).thenReturn(1);
        when(depositEventMapper.insert(any(DeviceRentalDepositEvent.class))).thenReturn(1);
        when(billMapper.insert(any(DeviceRentalBill.class))).thenReturn(1);
    }

    private DeviceInfo rentalDevice(String deviceId, long rentCents) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName("柜-" + deviceId);
        device.setCoopMode("RENTAL");
        device.setRentCents(rentCents);
        device.setSalesLocked(false);
        return device;
    }

    private DeviceRentalDeposit heldDeposit(long depositId, long amount) {
        DeviceRentalDeposit deposit = new DeviceRentalDeposit();
        deposit.setDepositId(depositId);
        deposit.setDeviceId("D1");
        deposit.setAmountCents(amount);
        deposit.setBalanceCents(amount);
        deposit.setStatus(DeviceRentalDeposit.STATUS_HELD);
        return deposit;
    }

    // ── 押金 ──────────────────────────────────────────────────────────────

    @Test
    void registerDeposit_rejectsNonRentalDevice() {
        DeviceInfo device = rentalDevice("D1", 0);
        device.setCoopMode("SELF");
        when(deviceMapper.findById("D1")).thenReturn(Optional.of(device));

        assertThatThrownBy(() -> service.registerDeposit(9L, "D1", 50_000L, null))
                .hasMessageContaining("RENTAL");
    }

    @Test
    void registerDeposit_duplicateConflict() {
        when(deviceMapper.findById("D1")).thenReturn(Optional.of(rentalDevice("D1", 0)));
        when(depositMapper.findByDevice("D1")).thenReturn(Optional.of(heldDeposit(1L, 50_000L)));

        assertThatThrownBy(() -> service.registerDeposit(9L, "D1", 50_000L, null))
                .hasMessageContaining("已登记");
    }

    @Test
    void deduct_withoutReason_rejected() {
        when(depositMapper.selectById(1L)).thenReturn(heldDeposit(1L, 50_000L));

        assertThatThrownBy(() -> service.deduct(9L, 1L, 10_000L, " "))
                .hasMessageContaining("依据");
    }

    @Test
    void deduct_overBalance_rejected() {
        when(depositMapper.selectById(1L)).thenReturn(heldDeposit(1L, 50_000L));
        when(depositMapper.adjustBalance(1L, -60_000L)).thenReturn(0);

        assertThatThrownBy(() -> service.deduct(9L, 1L, 60_000L, "欠租抵扣"))
                .hasMessageContaining("超过");
        verify(depositEventMapper, never()).insert(any(DeviceRentalDepositEvent.class));
    }

    @Test
    void deduct_success_writesNegativeEvent() {
        when(depositMapper.selectById(1L)).thenReturn(heldDeposit(1L, 50_000L));
        when(depositMapper.adjustBalance(1L, -10_000L)).thenReturn(1);
        when(depositMapper.selectById(1L)).thenReturn(heldDeposit(1L, 50_000L));

        service.deduct(9L, 1L, 10_000L, "冲抵 2026-08 租金");

        ArgumentCaptor<DeviceRentalDepositEvent> captor = ArgumentCaptor.forClass(DeviceRentalDepositEvent.class);
        verify(depositEventMapper).insert(captor.capture());
        assertThat(captor.getValue().getAmountCents()).isEqualTo(-10_000L);
        assertThat(captor.getValue().getEventType()).isEqualTo(DeviceRentalDeposit.EVENT_DEDUCT);
    }

    @Test
    void settle_writesReturnEventAndMarksSettled() {
        when(depositMapper.selectById(1L)).thenReturn(heldDeposit(1L, 50_000L));
        when(depositMapper.adjustBalance(1L, -50_000L)).thenReturn(1);

        DeviceRentalDeposit settled = service.settle(9L, 1L, null);

        assertThat(settled.getStatus()).isEqualTo(DeviceRentalDeposit.STATUS_SETTLED);
        assertThat(settled.getSettledAt()).isNotNull();
        ArgumentCaptor<DeviceRentalDepositEvent> captor = ArgumentCaptor.forClass(DeviceRentalDepositEvent.class);
        verify(depositEventMapper).insert(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(DeviceRentalDeposit.EVENT_RETURN);
        assertThat(captor.getValue().getAmountCents()).isEqualTo(-50_000L);
    }

    // ── 出账 ──────────────────────────────────────────────────────────────

    @Test
    void generateBill_rejectsZeroRent() {
        when(deviceMapper.findById("D1")).thenReturn(Optional.of(rentalDevice("D1", 0)));

        assertThatThrownBy(() -> service.generateBill(9L, "D1", "2026-10"))
                .hasMessageContaining("rentCents");
    }

    @Test
    void generateBill_beforeRentStartYm_rejected() {
        DeviceInfo device = rentalDevice("D1", 30_000L);
        device.setRentStartYm("2026-10");
        when(deviceMapper.findById("D1")).thenReturn(Optional.of(device));

        assertThatThrownBy(() -> service.generateBill(9L, "D1", "2026-09"))
                .hasMessageContaining("起租账期");
    }

    @Test
    void generateBill_idempotentPerMonth() {
        when(deviceMapper.findById("D1")).thenReturn(Optional.of(rentalDevice("D1", 30_000L)));
        when(billMapper.countNonVoidByDeviceAndMonth("D1", "2026-10")).thenReturn(1L);

        assertThatThrownBy(() -> service.generateBill(9L, "D1", "2026-10"))
                .hasMessageContaining("已出账");
    }

    // ── 日检：欠租锁柜 / 结清解锁 ─────────────────────────────────────────

    @Test
    void dailyCheck_overdueBill_locksDevice() {
        DeviceInfo device = rentalDevice("D1", 30_000L);
        when(deviceMapper.selectList(any())).thenReturn(List.of(device));
        when(deviceMapper.findById("D1")).thenReturn(Optional.of(device));
        when(billMapper.countNonVoidByDeviceAndMonth("D1", "2026-10")).thenReturn(1L);
        DeviceRentalBill unpaid = new DeviceRentalBill();
        unpaid.setBillId(7L);
        unpaid.setDeviceId("D1");
        unpaid.setBillMonth("2026-08");
        when(billMapper.findUnpaidUpTo("2026-09")).thenReturn(List.of(unpaid));

        String summary = service.runDailyCheck();

        verify(salesLockService).applySalesLock(eq(null), eq(device), eq(true),
                contains(DeviceRentalService.RENTAL_LOCK_PREFIX), eq(true));
        assertThat(summary).contains("欠租锁柜 1 台");
        assertThat(unpaid.getLockAt()).isNotNull();
    }

    @Test
    void dailyCheck_settled_unlocksOnlyRentalReasonLock() {
        DeviceInfo device = rentalDevice("D1", 30_000L);
        device.setSalesLocked(true);
        device.setSalesLockReason(DeviceRentalService.RENTAL_LOCK_PREFIX + "：2026-08 起租金未结清");
        when(deviceMapper.selectList(any())).thenReturn(List.of(device));
        when(billMapper.countNonVoidByDeviceAndMonth("D1", "2026-10")).thenReturn(1L);
        when(billMapper.findUnpaidUpTo("2026-09")).thenReturn(List.of());

        String summary = service.runDailyCheck();

        verify(salesLockService).applySalesLock(eq(null), eq(device), eq(false),
                contains("解除欠租锁柜"), eq(true));
        assertThat(summary).contains("结清解锁 1 台");
    }

    @Test
    void dailyCheck_neverTouchesOtherReasonLocks() {
        DeviceInfo device = rentalDevice("D1", 30_000L);
        device.setSalesLocked(true);
        device.setSalesLockReason("风控锁机");
        when(deviceMapper.selectList(any())).thenReturn(List.of(device));
        when(billMapper.countNonVoidByDeviceAndMonth("D1", "2026-10")).thenReturn(1L);
        when(billMapper.findUnpaidUpTo("2026-09")).thenReturn(List.of());

        service.runDailyCheck();

        verify(salesLockService, never()).applySalesLock(any(), any(), anyBoolean(), anyString(), anyBoolean());
    }

    @Test
    void dailyCheck_alreadyLocked_noDuplicateCommand() {
        DeviceInfo device = rentalDevice("D1", 30_000L);
        device.setSalesLocked(true);
        when(deviceMapper.selectList(any())).thenReturn(List.of(device));
        when(deviceMapper.findById("D1")).thenReturn(Optional.of(device));
        when(billMapper.countNonVoidByDeviceAndMonth("D1", "2026-10")).thenReturn(1L);
        DeviceRentalBill unpaid = new DeviceRentalBill();
        unpaid.setBillId(7L);
        unpaid.setDeviceId("D1");
        unpaid.setBillMonth("2026-08");
        when(billMapper.findUnpaidUpTo("2026-09")).thenReturn(List.of(unpaid));

        service.runDailyCheck();

        verify(salesLockService, never()).applySalesLock(any(), any(), eq(true), anyString(), anyBoolean());
        assertThat(unpaid.getLockAt()).isNotNull();
    }
}
