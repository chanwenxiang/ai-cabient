package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PlatformFundAccount;
import com.aicabinet.trade.domain.PlatformFundFlow;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PlatformFundAccountMapper;
import com.aicabinet.trade.mapper.PlatformFundFlowMapper;
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
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CB-033 平台资金池台账单测（含铁律负向：备付不足必须给出拦截原因 + 告警）。
 */
class PlatformFundServiceTest {

    private PlatformFundAccountMapper accountMapper;
    private PlatformFundFlowMapper flowMapper;
    private MerchantWithdrawRequestMapper withdrawMapper;
    private OrderRevenueSplitMapper splitMapper;
    private SystemConfigService systemConfigService;
    private OpsAlertDispatcher alertDispatcher;
    private ScheduledTaskService taskService;
    private PlatformFundService service;

    @BeforeEach
    void setUp() {
        accountMapper = mock(PlatformFundAccountMapper.class);
        flowMapper = mock(PlatformFundFlowMapper.class);
        withdrawMapper = mock(MerchantWithdrawRequestMapper.class);
        splitMapper = mock(OrderRevenueSplitMapper.class);
        systemConfigService = mock(SystemConfigService.class);
        alertDispatcher = mock(OpsAlertDispatcher.class);
        // 钉在 2026-10-12 08:00 上海时间，跨月边界可预测
        Clock clock = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        taskService = mock(ScheduledTaskService.class);
        when(taskService.tryBegin(anyString(), anyLong())).thenReturn(true);
        service = new PlatformFundService(accountMapper, flowMapper, withdrawMapper, splitMapper,
                systemConfigService, alertDispatcher, taskService, clock);
        when(systemConfigService.getDouble(anyString(), anyDouble()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(flowMapper.insert(any(PlatformFundFlow.class))).thenReturn(1);
    }

    private PlatformFundAccount wechatAccount(long balanceCents) {
        PlatformFundAccount account = new PlatformFundAccount();
        account.setAccountId(1L);
        account.setChannel("WECHAT");
        account.setAccountName("微信运营账户");
        account.setBalanceCents(balanceCents);
        account.setStatus("ACTIVE");
        return account;
    }

    // ── gatePayout ────────────────────────────────────────────────────────

    @Test
    void gatePayout_noAccount_softStartAlertAndAllow() {
        when(accountMapper.findByChannel("WECHAT")).thenReturn(Optional.empty());

        assertThat(service.gatePayout("WECHAT", 1_000L, 9L)).isEmpty();
        verify(alertDispatcher).trySend(eq(PlatformFundService.ALERT_SHORT), anyString(), contains("未登记"), any());
    }

    @Test
    void gatePayout_sufficient_allowWithoutAlert() {
        when(accountMapper.findByChannel("WECHAT")).thenReturn(Optional.of(wechatAccount(10_000L)));
        when(withdrawMapper.sumNetPendingByChannel("WECHAT", 9L)).thenReturn(2_000L);

        assertThat(service.gatePayout("WECHAT", 5_000L, 9L)).isEmpty();
        verify(alertDispatcher, never()).trySend(anyString(), anyString(), anyString(), any());
    }

    @Test
    void gatePayout_insufficient_blockReasonAndAlert() {
        when(accountMapper.findByChannel("WECHAT")).thenReturn(Optional.of(wechatAccount(3_000L)));
        when(withdrawMapper.sumNetPendingByChannel("WECHAT", 9L)).thenReturn(2_000L);

        // 余额 30 元 − 在途 20 元 < 本次 15 元 ⇒ 拦截
        Optional<String> reason = service.gatePayout("WECHAT", 1_500L, 9L);
        assertThat(reason).isPresent();
        assertThat(reason.get()).contains("备付金不足");
        verify(alertDispatcher).trySend(eq(PlatformFundService.ALERT_SHORT), anyString(), contains("充值"), any());
    }

    @Test
    void gatePayout_exactlyEqual_allow() {
        when(accountMapper.findByChannel("WECHAT")).thenReturn(Optional.of(wechatAccount(3_500L)));
        when(withdrawMapper.sumNetPendingByChannel("WECHAT", 9L)).thenReturn(2_000L);

        // 余额 35 元 − 在途 20 元 = 本次 15 元 ⇒ 恰好放行
        assertThat(service.gatePayout("WECHAT", 1_500L, 9L)).isEmpty();
    }

    // ── recordPayoutQuietly ───────────────────────────────────────────────

    @Test
    void recordPayout_missingAccount_alertsAndDoesNotThrow() {
        when(accountMapper.findByChannel("WECHAT")).thenReturn(Optional.empty());

        service.recordPayoutQuietly("WECHAT", 5_000L, 42L);

        verify(flowMapper, never()).insert(any(PlatformFundFlow.class));
        verify(alertDispatcher).trySend(eq(PlatformFundService.ALERT_SHORT), anyString(), contains("未扣减"), any());
    }

    @Test
    void recordPayout_success_writesNegativeFlow() {
        PlatformFundAccount account = wechatAccount(10_000L);
        when(accountMapper.findByChannel("WECHAT")).thenReturn(Optional.of(account));
        when(accountMapper.findById(1L)).thenReturn(Optional.of(account));
        when(accountMapper.adjustBalance(1L, -5_000L)).thenReturn(true);

        service.recordPayoutQuietly("WECHAT", 5_000L, 42L);

        ArgumentCaptor<PlatformFundFlow> captor = ArgumentCaptor.forClass(PlatformFundFlow.class);
        verify(flowMapper).insert(captor.capture());
        assertThat(captor.getValue().getAmountCents()).isEqualTo(-5_000L);
        assertThat(captor.getValue().getFlowType()).isEqualTo(PlatformFundFlow.TYPE_PAYOUT);
        assertThat(captor.getValue().getRefId()).isEqualTo("42");
    }

    // ── 台账登记 ──────────────────────────────────────────────────────────

    @Test
    void recharge_increasesBalanceAndWritesFlow() {
        PlatformFundAccount account = wechatAccount(1_000L);
        // 模拟 DB：adjustBalance 原子推进后 findById 读到的是新余额（recordFlow 内部也读一次 balanceAfter）
        when(accountMapper.findById(1L)).thenReturn(Optional.of(wechatAccount(3_500L)));
        when(accountMapper.adjustBalance(1L, 2_500L)).thenReturn(true);

        PlatformFundAccount after = service.recharge(1L, 2_500L, 77L, "财务充值");

        assertThat(after.getBalanceCents()).isEqualTo(3_500L);
        verify(accountMapper).adjustBalance(1L, 2_500L);
        ArgumentCaptor<PlatformFundFlow> captor = ArgumentCaptor.forClass(PlatformFundFlow.class);
        verify(flowMapper).insert(captor.capture());
        assertThat(captor.getValue().getFlowType()).isEqualTo(PlatformFundFlow.TYPE_RECHARGE);
        assertThat(captor.getValue().getOperatorId()).isEqualTo(77L);
    }

    @Test
    void sweep_takesPositiveAmountAsOutflow() {
        PlatformFundAccount account = wechatAccount(10_000L);
        when(accountMapper.findById(1L)).thenReturn(Optional.of(account));
        when(accountMapper.adjustBalance(1L, -4_000L)).thenReturn(true);

        service.sweep(1L, 4_000L, 77L, "归集到对公");

        verify(accountMapper).adjustBalance(1L, -4_000L);
    }

    @Test
    void adjust_withoutReason_rejected() {
        assertThatThrownBy(() -> service.adjust(1L, 100L, 77L, " "))
                .hasMessageContaining("原因");
    }

    @Test
    void createAccount_duplicateChannel_conflict() {
        when(accountMapper.findByChannel("WECHAT")).thenReturn(Optional.of(wechatAccount(0L)));

        assertThatThrownBy(() -> service.createAccount("wechat", "重复账户", 0L, 77L, null))
                .hasMessageContaining("已登记");
    }

    // ── runDailyCheck ─────────────────────────────────────────────────────

    @Test
    void dailyCheck_noAccounts_alertsSoftStart() {
        when(accountMapper.selectList(null)).thenReturn(List.of());

        String summary = service.runDailyCheck();

        assertThat(summary).contains("未登记");
        verify(alertDispatcher).trySend(eq(PlatformFundService.ALERT_PLAN), anyString(), anyString(), any());
    }

    @Test
    void dailyCheck_shortfall_alertsPlan() {
        PlatformFundAccount account = wechatAccount(1_000L);
        when(accountMapper.selectList(null)).thenReturn(List.of(account));
        when(withdrawMapper.sumNetPendingByChannel("WECHAT", null)).thenReturn(3_000L);
        when(flowMapper.countByTypeBetween(eq(PlatformFundFlow.TYPE_SWEEP), any(Instant.class), any(Instant.class)))
                .thenReturn(0L);
        when(splitMapper.sumPlatformCentsBetween(any(Instant.class), any(Instant.class))).thenReturn(0L);

        service.runDailyCheck();

        verify(alertDispatcher).trySend(eq(PlatformFundService.ALERT_PLAN), anyString(), contains("请今日充值"), any());
    }

    @Test
    void dailyCheck_reconMismatch_alertsRecon() {
        PlatformFundAccount account = wechatAccount(100_000L);
        when(accountMapper.selectList(null)).thenReturn(List.of(account));
        when(withdrawMapper.sumNetPendingByChannel("WECHAT", null)).thenReturn(0L);
        when(flowMapper.countByTypeBetween(eq(PlatformFundFlow.TYPE_SWEEP), any(Instant.class), any(Instant.class)))
                .thenReturn(3L);
        when(flowMapper.sumAmountByTypeBetween(eq(PlatformFundFlow.TYPE_SWEEP), any(Instant.class), any(Instant.class)))
                .thenReturn(50_000L);
        when(splitMapper.sumPlatformCentsBetween(any(Instant.class), any(Instant.class))).thenReturn(80_000L);

        String summary = service.runDailyCheck();

        // 差异 30 元 > 容差 10 元 ⇒ 告警
        verify(alertDispatcher).trySend(eq(PlatformFundService.ALERT_RECON), anyString(), contains("超容差"), any());
        assertThat(summary).contains("超容差");
    }

    @Test
    void dailyCheck_reconBalanced_noAlert() {
        PlatformFundAccount account = wechatAccount(100_000L);
        when(accountMapper.selectList(null)).thenReturn(List.of(account));
        when(withdrawMapper.sumNetPendingByChannel("WECHAT", null)).thenReturn(0L);
        when(flowMapper.countByTypeBetween(eq(PlatformFundFlow.TYPE_SWEEP), any(Instant.class), any(Instant.class)))
                .thenReturn(2L);
        when(flowMapper.sumAmountByTypeBetween(eq(PlatformFundFlow.TYPE_SWEEP), any(Instant.class), any(Instant.class)))
                .thenReturn(80_000L);
        when(splitMapper.sumPlatformCentsBetween(any(Instant.class), any(Instant.class))).thenReturn(80_050L);

        String summary = service.runDailyCheck();

        // 差异 0.5 元 < 容差 10 元 ⇒ 不告警
        verify(alertDispatcher, never()).trySend(eq(PlatformFundService.ALERT_RECON), anyString(), anyString(), any());
        assertThat(summary).contains("归集核对平");
    }
}
