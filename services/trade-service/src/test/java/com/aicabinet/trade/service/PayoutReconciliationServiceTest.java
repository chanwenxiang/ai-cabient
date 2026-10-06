package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.LineWithdrawRequest;
import com.aicabinet.trade.domain.MerchantWithdrawRequest;
import com.aicabinet.trade.mapper.LineWalletLedgerMapper;
import com.aicabinet.trade.mapper.LineWithdrawRequestMapper;
import com.aicabinet.trade.mapper.MerchantWalletLedgerMapper;
import com.aicabinet.trade.mapper.MerchantWithdrawRequestMapper;
import com.aicabinet.trade.payout.PayoutChannelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V308：出款侧对账（本地自洽口径）。
 *
 * <p><b>要守的恒等式</b>：{@code Σ(单.amount) − Σ(单.fee) == Σ|WITHDRAW_PAID|}
 * 且 {@code Σ(单.fee) == Σ|WITHDRAW_FEE|}。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("V308 出款侧对账")
class PayoutReconciliationServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Mock private MerchantWithdrawRequestMapper withdrawMapper;
    @Mock private LineWithdrawRequestMapper lineWithdrawMapper;
    @Mock private MerchantWalletLedgerMapper ledgerMapper;
    @Mock private LineWalletLedgerMapper lineLedgerMapper;
    @Mock private PayoutChannelRegistry channelRegistry;

    private PayoutReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new PayoutReconciliationService(withdrawMapper, lineWithdrawMapper,
                ledgerMapper, lineLedgerMapper, channelRegistry);
        // 线长侧默认无单、无流水；只关心商户侧的用例不需为它写 stub
        lenient().when(lineWithdrawMapper.findByStatusAndPaidAtBetween(
                anyString(), any(Instant.class), any(Instant.class))).thenReturn(List.of());
    }

    private static MerchantWithdrawRequest paid(String channel, long gross, long fee) {
        MerchantWithdrawRequest r = new MerchantWithdrawRequest();
        r.setRequestId(1L);
        r.setMerchantId("M-1");
        r.setPayChannel(channel);
        r.setAmountCents(gross);
        r.setFeeCents(fee);
        r.setStatus("PAID");
        r.setPaidAt(Instant.now());
        return r;
    }

    @Test
    @DisplayName("三方自洽（单/流水/手续费全对）⇒ balanced")
    void allConsistent_balanced() {
        // 单：1000 分毛额，100 分手续费 ⇒净额 900
        lenient().when(withdrawMapper.findByStatusAndPaidAtBetween(
                anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(paid("MOCK", 1_000L, 100L)));
        when(ledgerMapper.sumAmountByEntryTypeBetween(anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(900L, 100L);   // 先 WITHDRAW_PAID，后 WITHDRAW_FEE

        var report = service.reconcile(LocalDate.now(ZONE));

        assertTrue(report.balanced());
        assertEquals(0L, report.netDiffCents());
        assertEquals(0L, report.feeDiffCents());
        assertEquals(1_000L, report.grossCents());
        assertEquals(900L, report.netCents());
        assertEquals(100L, report.feeCents());
    }

    @Test
    @DisplayName("🔴 钱包出款流水少于提现单 ⇒ 不平且被点名")
    void netMismatch_detected() {
        lenient().when(withdrawMapper.findByStatusAndPaidAtBetween(
                anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(paid("MOCK", 1_000L, 100L)));
        // 流水只有 800，差 100
        when(ledgerMapper.sumAmountByEntryTypeBetween(anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(800L, 100L);

        var report = service.reconcile(LocalDate.now(ZONE));

        assertFalse(report.balanced());
        assertEquals(100L, report.netDiffCents());
        assertTrue(report.detail().get("issues").toString().contains("出款净额不平"));
    }

    @Test
    @DisplayName("🔴 手续费漏记（流水没有 WITHDRAW_FEE）⇒ 不平")
    void feeMissing_detected() {
        lenient().when(withdrawMapper.findByStatusAndPaidAtBetween(
                anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(paid("MOCK", 1_000L, 100L)));
        // 净额对、手续费流水为 0
        when(ledgerMapper.sumAmountByEntryTypeBetween(anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(900L, 0L);

        var report = service.reconcile(LocalDate.now(ZONE));

        assertFalse(report.balanced());
        assertEquals(100L, report.feeDiffCents());
        assertTrue(report.detail().get("issues").toString().contains("手续费不平"));
    }

    @Test
    @DisplayName("当日无提现 ⇒ 平（不报假警）")
    void noData_balanced() {
        lenient().when(withdrawMapper.findByStatusAndPaidAtBetween(
                anyString(), any(Instant.class), any(Instant.class))).thenReturn(List.of());
        when(ledgerMapper.sumAmountByEntryTypeBetween(anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(0L, 0L);

        var report = service.reconcile(LocalDate.now(ZONE));

        assertTrue(report.balanced());
        assertEquals(0, report.paidCount());
    }

    @Test
    @DisplayName("🔴 时间窗必须有上界（否则会把历史全算进当日）")
    void timeWindow_hasUpperBound() {
        lenient().when(withdrawMapper.findByStatusAndPaidAtBetween(
                anyString(), any(Instant.class), any(Instant.class))).thenReturn(List.of());
        lenient().when(ledgerMapper.sumAmountByEntryTypeBetween(anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(0L);

        LocalDate day = LocalDate.of(2026, 10, 6);
        service.reconcile(day);

        var captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(ledgerMapper, org.mockito.Mockito.atLeastOnce())
                .sumAmountByEntryTypeBetween(anyString(), any(Instant.class), captor.capture());

        Instant expectedEnd = day.plusDays(1).atStartOfDay(ZONE).toInstant();
        assertTrue(captor.getAllValues().contains(expectedEnd),
                "对账必须传上界，否则当日口径会随历史增长而虚增");
    }

    @Test
    @DisplayName("🔴 报告必须诚实标注 scope=本地自洽（不得被当成已与渠道对平）")
    void scopeIsHonest() {
        lenient().when(withdrawMapper.findByStatusAndPaidAtBetween(
                anyString(), any(Instant.class), any(Instant.class))).thenReturn(List.of());
        lenient().when(ledgerMapper.sumAmountByEntryTypeBetween(anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(0L);

        var report = service.reconcile(LocalDate.now(ZONE));
        assertEquals("LOCAL_SELF_CONSISTENT_ONLY", report.detail().get("scope"));
        assertTrue(report.detail().get("scopeNote").toString().contains("未与渠道账单比对"));
    }

    @Test
    @DisplayName("🔴 线长侧出款也必须纳入（只对商户侧就是「少算一半」）")
    void lineWithdrawIncludedInRecon() {
        LineWithdrawRequest line = new LineWithdrawRequest();
        line.setRequestId(2L);
        line.setManagerId(7L);
        line.setPayChannel("WECHAT");
        line.setAmountCents(5_000L);
        line.setFeeCents(0L);
        line.setStatus("PAID");
        line.setPaidAt(Instant.now());
        lenient().when(lineWithdrawMapper.findByStatusAndPaidAtBetween(
                anyString(), any(Instant.class), any(Instant.class))).thenReturn(List.of(line));
        when(ledgerMapper.sumAmountByEntryTypeBetween(anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(5_000L, 0L);

        var report = service.reconcile(LocalDate.now(ZONE));
        assertTrue(report.balanced());
        assertEquals(5_000L, report.netCents(), "线长的 5000 分必须计入总额");
        assertEquals(1L, report.detail().get("linePaidCount"));
        @SuppressWarnings("unchecked")
        var byChannel = (java.util.Map<String, Object>) report.detail().get("byChannel");
        assertTrue(byChannel.containsKey("WECHAT"));
    }

    @Test
    @DisplayName("按通道分组：多通道金额各自可见")
    void groupedByChannel() {
        lenient().when(withdrawMapper.findByStatusAndPaidAtBetween(
                anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(
                        paid("WECHAT", 1_000L, 0L),      // 净额 1000
                        paid("BANK", 50_000L, 500L)));  // 净额 49500 ⇒ 合计 50500
        when(ledgerMapper.sumAmountByEntryTypeBetween(anyString(), any(Instant.class), any(Instant.class)))
                .thenReturn(50_500L, 500L);

        var report = service.reconcile(LocalDate.now(ZONE));
        assertTrue(report.balanced());
        assertEquals(50_500L, report.netCents());
        @SuppressWarnings("unchecked")
        var byChannel = (java.util.Map<String, Object>) report.detail().get("byChannel");
        assertTrue(byChannel.containsKey("WECHAT"));
        assertTrue(byChannel.containsKey("BANK"));
    }
}
