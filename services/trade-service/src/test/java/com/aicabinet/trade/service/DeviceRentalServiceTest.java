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
        deposit.setMerchantId("10001");
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
    void registerDeposit_removalSettlementParams_configurable() {
        // CB-044 拍板：撤机清算三件套登记可配、不写死
        when(deviceMapper.findById("D1")).thenReturn(Optional.of(rentalDevice("D1", 0)));
        when(depositMapper.findByDevice("D1")).thenReturn(Optional.empty());

        service.registerDeposit(9L, "D1", 50_000L, null, null, null, null, 5_000L, false, 3);

        ArgumentCaptor<DeviceRentalDeposit> captor = ArgumentCaptor.forClass(DeviceRentalDeposit.class);
        verify(depositMapper).insert(captor.capture());
        assertThat(captor.getValue().getInstallFeeDeductCents()).isEqualTo(5_000L);
        assertThat(captor.getValue().getInstallFeeClawbackEnabled()).isFalse();
        assertThat(captor.getValue().getContractYears()).isEqualTo(3);
    }

    @Test
    void registerDeposit_removalSettlementParams_defaultFeeZeroAndClawbackOff() {
        // 用户 2026-10-17 拍板：安装费**默认为 0=不收**、要收按柜登记时填（V383）；
        // 「倒扣商户余额」的追回开关自 DC-29（2026-10-16）改默认关 —— 资损面必须显式传 true 才开
        when(deviceMapper.findById("D1")).thenReturn(Optional.of(rentalDevice("D1", 0)));
        when(depositMapper.findByDevice("D1")).thenReturn(Optional.empty());

        service.registerDeposit(9L, "D1", 50_000L, null);

        ArgumentCaptor<DeviceRentalDeposit> captor = ArgumentCaptor.forClass(DeviceRentalDeposit.class);
        verify(depositMapper).insert(captor.capture());
        assertThat(captor.getValue().getInstallFeeDeductCents()).isZero();
        assertThat(captor.getValue().getInstallFeeClawbackEnabled()).isFalse();
        assertThat(captor.getValue().getContractYears()).isEqualTo(5);
    }

    @Test
    void registerDeposit_clawbackNull_notEnabledByDefault() {
        // 钉住 DC-29 语义反转：null ≠ 老系统的「开」，只有显式 true 才启用
        when(deviceMapper.findById("D1")).thenReturn(Optional.of(rentalDevice("D1", 0)));
        when(depositMapper.findByDevice("D1")).thenReturn(Optional.empty());

        service.registerDeposit(9L, "D1", 50_000L, null, null, null, null, null, null, null);

        ArgumentCaptor<DeviceRentalDeposit> captor = ArgumentCaptor.forClass(DeviceRentalDeposit.class);
        verify(depositMapper).insert(captor.capture());
        assertThat(captor.getValue().getInstallFeeClawbackEnabled()).isFalse();
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
        // CB-044：撤机清算含安装费扣减（默认 100 元）——事件与返还断言移至
        // settle_beforeContractExpiry_deductsInstallFeeAndReturnsRest（比例拆分钉住）
        DeviceRentalDeposit deposit = heldDeposit(1L, 50_000L);
        deposit.setInstallFeeDeductCents(0L);
        when(depositMapper.selectById(1L)).thenReturn(deposit);
        when(depositMapper.adjustBalance(1L, -50_000L)).thenReturn(1);

        DeviceRentalDeposit settled = service.settle(9L, 1L, null);

        assertThat(settled.getStatus()).isEqualTo(DeviceRentalDeposit.STATUS_SETTLED);
        assertThat(settled.getSettledAt()).isNotNull();
        ArgumentCaptor<DeviceRentalDepositEvent> captor = ArgumentCaptor.forClass(DeviceRentalDepositEvent.class);
        verify(depositEventMapper).insert(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(DeviceRentalDeposit.EVENT_RETURN);
        assertThat(captor.getValue().getAmountCents()).isEqualTo(-50_000L);
    }

    @Test
    void settle_defaultZeroFee_returnsBalanceInFull() {
        // 2026-10-17 拍板「安装费默认为 0」的正面钉桩：默认登记行（fee 未填/为 0）撤机时
        // 必须**全额返还**且**不写 DEPOSIT 事件** —— 运营台弹窗「返还剩余押金 ¥X」才与实扣一致（前后一致）。
        DeviceRentalDeposit deposit = heldDeposit(1L, 50_000L);
        deposit.setChargeMode("ONLINE");
        when(depositMapper.selectById(1L)).thenReturn(deposit);
        when(depositMapper.adjustBalance(1L, -50_000L)).thenReturn(1);

        service.settle(9L, 1L, null);

        verify(merchantWalletService).creditIfAbsent(eq("10001"), eq(50_000L), eq("DEPOSIT_RETURN"),
                eq("RENTAL_DEPOSIT_SETTLE"), eq("1"), eq("退租结算返还（柜机 D1）"));
        ArgumentCaptor<DeviceRentalDepositEvent> captor = ArgumentCaptor.forClass(DeviceRentalDepositEvent.class);
        verify(depositEventMapper).insert(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(DeviceRentalDeposit.EVENT_RETURN);
        assertThat(captor.getValue().getAmountCents()).isEqualTo(-50_000L);
    }

    @Test
    void settle_beforeContractExpiry_deductsInstallFeeAndReturnsRest() {
        // CB-044 三件套：未退完撤机 → 退剩余 − 安装费。V383 后安装费**默认 0**，
        // 所以这条钉的是「登记时按柜填了 100 元就真的扣 100 元」——费用必须由行上显式带值挣得。
        DeviceRentalDeposit deposit = heldDeposit(1L, 50_000L);
        deposit.setChargeMode("ONLINE");
        deposit.setInstallFeeDeductCents(10_000L);
        when(depositMapper.selectById(1L)).thenReturn(deposit);
        when(depositMapper.adjustBalance(1L, -50_000L)).thenReturn(1);

        service.settle(9L, 1L, null);

        ArgumentCaptor<DeviceRentalDepositEvent> captor = ArgumentCaptor.forClass(DeviceRentalDepositEvent.class);
        verify(depositEventMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues().get(0).getEventType()).isEqualTo(DeviceRentalDeposit.EVENT_RETURN);
        assertThat(captor.getAllValues().get(0).getAmountCents()).isEqualTo(-40_000L);
        assertThat(captor.getAllValues().get(1).getEventType()).isEqualTo(DeviceRentalDeposit.EVENT_DEDUCT);
        assertThat(captor.getAllValues().get(1).getAmountCents()).isEqualTo(-10_000L);
        verify(merchantWalletService).creditIfAbsent(eq("10001"), eq(40_000L), eq("DEPOSIT_RETURN"),
                eq("RENTAL_DEPOSIT_SETTLE"), eq("1"), contains("安装费"));
    }

    @Test
    void settle_depositExhaustedAndContractExpired_grantsRewardWithoutFee() {
        // 押金已按月退完 + 满 5 年 → 发期满奖励（不扣安装费）；refId 与月度链同款 ⇒ 幂等不双发
        DeviceRentalDeposit deposit = heldDeposit(1L, 0L);
        deposit.setChargeMode("ONLINE");
        deposit.setCreatedAt(Instant.parse("2020-10-12T00:00:00Z"));
        deposit.setExpireRewardCents(20_000L);
        when(depositMapper.selectById(1L)).thenReturn(deposit);

        service.settle(9L, 1L, null);

        verify(merchantWalletService).creditIfAbsent(eq("10001"), eq(20_000L), eq("DEPOSIT_EXPIRE_REWARD"),
                eq("RENTAL_DEPOSIT_REFUND"), eq("1|REWARD"), anyString());
        verify(merchantWalletService, never()).debitIfAbsentAllowNegative(anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyString());
        assertThat(deposit.getStatus()).isEqualTo(DeviceRentalDeposit.STATUS_SETTLED);
    }

    @Test
    void settle_depositExhaustedBeforeExpiry_clawsBackInstallFee() {
        // 押金已按月退完 + 未满 5 年 + **登记时显式开了追回** → 向商户余额倒扣安装费（AllowNegative）
        DeviceRentalDeposit deposit = heldDeposit(1L, 0L);
        deposit.setChargeMode("ONLINE");
        deposit.setCreatedAt(Instant.parse("2025-10-12T00:00:00Z"));
        deposit.setInstallFeeDeductCents(10_000L);
        deposit.setInstallFeeClawbackEnabled(true);
        when(depositMapper.selectById(1L)).thenReturn(deposit);

        service.settle(9L, 1L, null);

        verify(merchantWalletService).debitIfAbsentAllowNegative(eq("10001"), eq(10_000L),
                eq("DEPOSIT_INSTALL_FEE_CLAWBACK"), eq("RENTAL_DEPOSIT_SETTLE"), eq("1|CLAWBACK"),
                anyString());
        verify(merchantWalletService, never()).creditIfAbsent(anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void settle_clawbackDisabled_noClawback() {
        DeviceRentalDeposit deposit = heldDeposit(1L, 0L);
        deposit.setChargeMode("ONLINE");
        deposit.setCreatedAt(Instant.parse("2025-10-12T00:00:00Z"));
        deposit.setInstallFeeClawbackEnabled(false);
        when(depositMapper.selectById(1L)).thenReturn(deposit);

        service.settle(9L, 1L, null);

        verify(merchantWalletService, never()).debitIfAbsentAllowNegative(anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyString());
        assertThat(deposit.getStatus()).isEqualTo(DeviceRentalDeposit.STATUS_SETTLED);
    }

    @Test
    void settle_clawbackNull_treatedAsDisabled() {
        // DC-29 语义：字段缺失（历史行/直连 SQL 写入）一律按「未开启」处理，不自动倒扣商户余额
        DeviceRentalDeposit deposit = heldDeposit(1L, 0L);
        deposit.setChargeMode("ONLINE");
        deposit.setCreatedAt(Instant.parse("2025-10-12T00:00:00Z"));
        deposit.setInstallFeeClawbackEnabled(null);
        when(depositMapper.selectById(1L)).thenReturn(deposit);

        service.settle(9L, 1L, null);

        verify(merchantWalletService, never()).debitIfAbsentAllowNegative(anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyString());
        assertThat(deposit.getStatus()).isEqualTo(DeviceRentalDeposit.STATUS_SETTLED);
    }

    // ── 押金按月退还（deposit-monthly-refund，审计 🔴3 超退/幂等回归）─────────

    private DeviceRentalDeposit monthlyRefundDeposit(long amount, long unit, int startMonth, String createdAt) {
        DeviceRentalDeposit deposit = heldDeposit(1L, amount);
        deposit.setChargeMode("ONLINE");
        deposit.setMonthlyRefundCents(unit);
        deposit.setRefundStartMonth(startMonth);
        deposit.setCreatedAt(Instant.parse(createdAt));
        return deposit;
    }

    @Test
    void runMonthlyRefunds_lastMonthRefundsRemainingBalance_notFixedUnit() {
        // 50000 / 月退 30000 → threshold=ceil(50000/30000)+1-1=2，末月（第 2 期）余额只剩 20000。
        // 回归钉桩：末月必须退**余额**而非固定 unit，否则退 30000 就是超退 10000（资损）。
        DeviceRentalDeposit deposit = monthlyRefundDeposit(50_000L, 30_000L, 1, "2026-09-01T00:00:00Z");
        deposit.setBalanceCents(20_000L);
        when(depositMapper.selectList(any())).thenReturn(List.of(deposit));
        when(depositMapper.adjustBalance(1L, -20_000L)).thenReturn(1);
        when(merchantWalletService.creditIfAbsent(anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(true);
        when(depositMapper.selectById(1L)).thenReturn(deposit);

        assertThat(service.runMonthlyRefunds()).isEqualTo(1);

        verify(depositMapper).adjustBalance(1L, -20_000L);
        verify(merchantWalletService).creditIfAbsent(eq("10001"), eq(20_000L), eq("DEPOSIT_RETURN"),
                eq("RENTAL_DEPOSIT_REFUND"), eq("1|2"), anyString());
        ArgumentCaptor<DeviceRentalDepositEvent> captor =
                ArgumentCaptor.forClass(DeviceRentalDepositEvent.class);
        verify(depositEventMapper).insert(captor.capture());
        assertThat(captor.getValue().getAmountCents()).isEqualTo(-20_000L);
        assertThat(deposit.getStatus()).isEqualTo(DeviceRentalDeposit.STATUS_SETTLED);
    }

    @Test
    void runMonthlyRefunds_lastMonthWithReward_creditsRewardOnce() {
        // 末月结清 + 期满奖励（老系统口径：奖励 refId=押金|REWARD，与 settle 的同 refId 互斥不双发）
        DeviceRentalDeposit deposit = monthlyRefundDeposit(50_000L, 50_000L, 1, "2026-10-01T00:00:00Z");
        deposit.setExpireRewardCents(20_000L);
        when(depositMapper.selectList(any())).thenReturn(List.of(deposit));
        when(depositMapper.adjustBalance(1L, -50_000L)).thenReturn(1);
        when(merchantWalletService.creditIfAbsent(anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(true);
        when(depositMapper.selectById(1L)).thenReturn(deposit);

        assertThat(service.runMonthlyRefunds()).isEqualTo(1);

        verify(merchantWalletService).creditIfAbsent(eq("10001"), eq(50_000L), eq("DEPOSIT_RETURN"),
                eq("RENTAL_DEPOSIT_REFUND"), eq("1|1"), anyString());
        verify(merchantWalletService).creditIfAbsent(eq("10001"), eq(20_000L), eq("DEPOSIT_EXPIRE_REWARD"),
                eq("RENTAL_DEPOSIT_REFUND"), eq("1|REWARD"), anyString());
    }

    @Test
    void runMonthlyRefunds_middleMonthRefundsUnitAndKeepsHeld() {
        // 50000 / 月退 10000 → threshold=5；当前第 3 期只退 unit，且不置结清
        DeviceRentalDeposit deposit = monthlyRefundDeposit(50_000L, 10_000L, 1, "2026-08-01T00:00:00Z");
        when(depositMapper.selectList(any())).thenReturn(List.of(deposit));
        when(depositMapper.adjustBalance(1L, -10_000L)).thenReturn(1);
        when(merchantWalletService.creditIfAbsent(anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(true);

        assertThat(service.runMonthlyRefunds()).isEqualTo(1);

        verify(merchantWalletService).creditIfAbsent(eq("10001"), eq(10_000L), eq("DEPOSIT_RETURN"),
                eq("RENTAL_DEPOSIT_REFUND"), eq("1|3"), anyString());
        verify(depositMapper, never()).updateById(any());
        assertThat(deposit.getStatus()).isEqualTo(DeviceRentalDeposit.STATUS_HELD);
    }

    @Test
    void runMonthlyRefunds_beforeStartMonth_nothingMoves() {
        // 起始月 3（老系统默认）：登记后第 2 个月还没到退押窗口，一分都不能动
        DeviceRentalDeposit deposit = monthlyRefundDeposit(50_000L, 10_000L, 3, "2026-09-01T00:00:00Z");
        when(depositMapper.selectList(any())).thenReturn(List.of(deposit));

        assertThat(service.runMonthlyRefunds()).isZero();

        verify(depositMapper, never()).adjustBalance(anyLong(), anyLong());
        verify(merchantWalletService, never()).creditIfAbsent(anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void runMonthlyRefunds_walletAlreadyCredited_restoresLedgerAndCountsNothing() {
        // 重跑幂等：creditIfAbsent=false 表示该期钱包流水已存在 ⇒ 台账刚扣的余额要回补，
        // 且不计入处理笔数、不写事件（否则「已退额」虚增而钱没再退）
        DeviceRentalDeposit deposit = monthlyRefundDeposit(50_000L, 10_000L, 1, "2026-08-01T00:00:00Z");
        when(depositMapper.selectList(any())).thenReturn(List.of(deposit));
        when(depositMapper.adjustBalance(1L, -10_000L)).thenReturn(1);
        when(merchantWalletService.creditIfAbsent(anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(false);

        assertThat(service.runMonthlyRefunds()).isZero();

        verify(depositMapper).adjustBalance(1L, -10_000L);
        verify(depositMapper).adjustBalance(1L, 10_000L);
        verify(depositEventMapper, never()).insert(any());
    }

    @Test
    void runMonthlyRefunds_ledgerGuardFails_doesNotCreditWallet() {
        // 顺序铁律（审计 🔴3）：adjustBalance 守卫返回 0（余额已被并发推进）⇒ 本轮跳过，
        // 绝不出现「钱包已入账、押金台账未减」的超额退还
        DeviceRentalDeposit deposit = monthlyRefundDeposit(50_000L, 10_000L, 1, "2026-08-01T00:00:00Z");
        when(depositMapper.selectList(any())).thenReturn(List.of(deposit));
        when(depositMapper.adjustBalance(1L, -10_000L)).thenReturn(0);

        assertThat(service.runMonthlyRefunds()).isZero();

        verify(merchantWalletService, never()).creditIfAbsent(anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyString());
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
