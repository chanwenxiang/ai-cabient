package com.aicabinet.trade.service;

import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.reconciliation.PlatformBillLine;
import com.aicabinet.trade.reconciliation.PlatformBillProviderRegistry;
import com.aicabinet.trade.service.support.ReconciliationServiceSupport;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.PaymentPlatformBillLineMapper;
import com.aicabinet.trade.mapper.PaymentReconciliationMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock private PaymentReconciliationMapper reconRepository;
    @Mock private PaymentPlatformBillLineMapper billLineRepository;
    @Mock private PaymentOperationMapper paymentOperationRepository;
    @Mock private RechargeOrderMapper rechargeRepository;
    @Mock private PlatformBillProviderRegistry billProviderRegistry;
    @Mock private CabinetMetrics cabinetMetrics;
    @Mock private DistributedLockService distributedLockService;
    @Mock private SystemConfigService systemConfigService;

    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        ReconciliationServiceSupport support = new ReconciliationServiceSupport(
                billLineRepository, paymentOperationRepository, rechargeRepository,
                billProviderRegistry, new ObjectMapper(), cabinetMetrics, distributedLockService);
        service = new ReconciliationService(reconRepository, support, null, null, systemConfigService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        org.mockito.Mockito.lenient().when(distributedLockService.tryLock(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        org.mockito.Mockito.lenient().when(paymentOperationRepository.sumGatewayRechargeRefundBetween(any(), any()))
                .thenReturn(0L);
        org.mockito.Mockito.lenient().when(paymentOperationRepository.sumRechargeRefundByChannel(any(), any(), anyString()))
                .thenReturn(0L);
    }

    @Test
    void runDaily_matchedWhenPlatformEqualsLedger() {
        LocalDate date = LocalDate.of(2024, 6, 1);
        ZoneId zone = ZoneId.systemDefault();
        Instant start = date.atStartOfDay(zone).toInstant();

        when(reconRepository.findByReconDateAndChannel(date, "MOCK")).thenReturn(Optional.empty());
        when(paymentOperationRepository.sumNetCashflowBetween(any(), any(), eq("MOCK"))).thenReturn(350L);
        when(rechargeRepository.sumPaidAmountBetween(any(), any())).thenReturn(0L);
        when(paymentOperationRepository.findDistinctCabinetOrderIdsBetween(any(), any(), eq("MOCK")))
                .thenReturn(List.of("ORD-1"));
        when(rechargeRepository.findPaidOrderIdsBetween(any(), any())).thenReturn(List.of());
        when(billProviderRegistry.fetchBill("MOCK", date)).thenReturn(List.of(
                new PlatformBillLine("P1", "ORD-1", 350, start, "PAY", null, "{}")
        ));
        when(reconRepository.save(any())).thenAnswer(inv -> {
            var r = inv.getArgument(0, com.aicabinet.trade.domain.PaymentReconciliation.class);
            if (r.getReconId() == null) {
                r.setReconId(1L);
            }
            return r;
        });

        var result = service.runDaily(100000001L, date, "MOCK");

        assertEquals("MATCHED", result.status());
        assertEquals(350, result.platformTotal());
        assertEquals(350, result.ledgerTotal());
        assertEquals(1, result.matchedCount());
        verify(cabinetMetrics, never()).recordReconciliationMismatch();
    }

    @Test
    void runDaily_recordsMismatchMetricWhenDiff() {
        LocalDate date = LocalDate.now();
        when(reconRepository.findByReconDateAndChannel(date, "WECHAT")).thenReturn(Optional.empty());
        when(paymentOperationRepository.sumNetCashflowBetween(any(), any(), eq("WECHAT"))).thenReturn(100L);
        when(rechargeRepository.sumPaidAmountBetween(any(), any())).thenReturn(0L);
        when(paymentOperationRepository.findDistinctCabinetOrderIdsBetween(any(), any(), eq("WECHAT")))
                .thenReturn(List.of());
        when(rechargeRepository.findPaidOrderIdsBetween(any(), any())).thenReturn(List.of());
        when(billProviderRegistry.fetchBill("WECHAT", date)).thenReturn(List.of(
                new PlatformBillLine("P9", "ORD-X", 500, Instant.now(), "WECHAT", null, "raw")
        ));
        when(reconRepository.save(any())).thenAnswer(inv -> {
            var r = inv.getArgument(0, com.aicabinet.trade.domain.PaymentReconciliation.class);
            if (r.getReconId() == null) r.setReconId(2L);
            return r;
        });

        var result = service.runDaily(100000001L, date, "WECHAT");

        assertEquals("MISMATCH", result.status());
        assertEquals(1, result.unmatchedCount());
        verify(cabinetMetrics).recordReconciliationMismatch();
        verify(billLineRepository).save(argThat(line -> !line.isMatched()));
    }

    @Test
    void runDaily_flagsLedgerOnlyOrdersAsMismatch() {
        LocalDate date = LocalDate.now();
        when(reconRepository.findByReconDateAndChannel(date, "MOCK")).thenReturn(Optional.empty());
        when(paymentOperationRepository.sumNetCashflowBetween(any(), any(), eq("MOCK"))).thenReturn(100L);
        when(rechargeRepository.sumPaidAmountBetween(any(), any())).thenReturn(0L);
        when(paymentOperationRepository.findDistinctCabinetOrderIdsBetween(any(), any(), eq("MOCK")))
                .thenReturn(List.of("ORD-LEDGER-ONLY"));
        when(rechargeRepository.findPaidOrderIdsBetween(any(), any())).thenReturn(List.of());
        when(billProviderRegistry.fetchBill("MOCK", date)).thenReturn(List.of());
        when(reconRepository.save(any())).thenAnswer(inv -> {
            var r = inv.getArgument(0, com.aicabinet.trade.domain.PaymentReconciliation.class);
            if (r.getReconId() == null) r.setReconId(3L);
            return r;
        });

        var result = service.runDaily(100000001L, date, "MOCK");

        assertEquals("MISMATCH", result.status());
        assertEquals(-100, result.diffCents());
        verify(cabinetMetrics).recordReconciliationMismatch();
        verify(reconRepository, atLeastOnce()).save(argThat(r ->
                r.getDetail() != null && r.getDetail().contains("ORD-LEDGER-ONLY")));
    }

    @Test
    void runDaily_includesRechargeInLedgerTotal() {
        LocalDate date = LocalDate.of(2024, 6, 2);
        when(reconRepository.findByReconDateAndChannel(date, "WECHAT")).thenReturn(Optional.empty());
        when(paymentOperationRepository.sumNetCashflowBetween(any(), any(), eq("WECHAT"))).thenReturn(200L);
        when(rechargeRepository.sumPaidAmountBetween(any(), any())).thenReturn(150L);
        when(paymentOperationRepository.findDistinctCabinetOrderIdsBetween(any(), any(), eq("WECHAT")))
                .thenReturn(List.of("ORD-1"));
        when(rechargeRepository.findPaidOrderIdsBetween(any(), any())).thenReturn(List.of("RCH-1"));
        when(billProviderRegistry.fetchBill("WECHAT", date)).thenReturn(List.of(
                new PlatformBillLine("P1", "ORD-1", 200, Instant.now(), "PAY", null, "{}"),
                new PlatformBillLine("P2", "RCH-1", 150, Instant.now(), "PAY", null, "{}")
        ));
        when(reconRepository.save(any())).thenAnswer(inv -> {
            var r = inv.getArgument(0, com.aicabinet.trade.domain.PaymentReconciliation.class);
            if (r.getReconId() == null) {
                r.setReconId(11L);
            }
            return r;
        });

        var result = service.runDaily(100000001L, date, "WECHAT");

        assertEquals("MATCHED", result.status());
        assertEquals(350, result.ledgerTotal());
        assertEquals(350, result.platformTotal());
    }

    @Test
    void runDaily_mockLedgerNetsGatewayRechargeRefunds() {
        LocalDate date = LocalDate.of(2024, 6, 3);
        when(reconRepository.findByReconDateAndChannel(date, "MOCK")).thenReturn(Optional.empty());
        when(paymentOperationRepository.sumNetCashflowBetween(any(), any(), eq("MOCK"))).thenReturn(0L);
        when(rechargeRepository.sumPaidAmountBetween(any(), any())).thenReturn(2700L);
        when(paymentOperationRepository.sumGatewayRechargeRefundBetween(any(), any())).thenReturn(450L);
        when(paymentOperationRepository.sumRechargeRefundByChannel(any(), any(), eq("MOCK"))).thenReturn(0L);
        when(paymentOperationRepository.findDistinctCabinetOrderIdsBetween(any(), any(), eq("MOCK")))
                .thenReturn(List.of());
        when(rechargeRepository.findPaidOrderIdsBetween(any(), any())).thenReturn(List.of("RCH-1"));
        when(billProviderRegistry.fetchBill("MOCK", date)).thenReturn(List.of(
                new PlatformBillLine("P-R1", "RCH-1", 2700, Instant.now(), "RECHARGE", null, "{}"),
                new PlatformBillLine("P-RF1", "RCH-1", -450, Instant.now(), "REFUND", null, "{}")
        ));
        when(reconRepository.save(any())).thenAnswer(inv -> {
            var r = inv.getArgument(0, com.aicabinet.trade.domain.PaymentReconciliation.class);
            if (r.getReconId() == null) {
                r.setReconId(12L);
            }
            return r;
        });

        var result = service.runDaily(100000001L, date, "MOCK");

        assertEquals("MATCHED", result.status());
        assertEquals(2250, result.platformTotal());
        assertEquals(2250, result.ledgerTotal());
        assertEquals(0, result.diffCents());
    }

    @Test
    void runDaily_wechatDoesNotDoubleSubtractRechargeRefund() {
        LocalDate date = LocalDate.of(2024, 6, 4);
        when(reconRepository.findByReconDateAndChannel(date, "WECHAT")).thenReturn(Optional.empty());
        // 净流入已含本渠道 RECHARGE_REFUND -450
        when(paymentOperationRepository.sumNetCashflowBetween(any(), any(), eq("WECHAT"))).thenReturn(-450L);
        when(rechargeRepository.sumPaidAmountBetween(any(), any())).thenReturn(2700L);
        when(paymentOperationRepository.sumGatewayRechargeRefundBetween(any(), any())).thenReturn(450L);
        when(paymentOperationRepository.sumRechargeRefundByChannel(any(), any(), eq("WECHAT"))).thenReturn(450L);
        when(paymentOperationRepository.findDistinctCabinetOrderIdsBetween(any(), any(), eq("WECHAT")))
                .thenReturn(List.of());
        when(rechargeRepository.findPaidOrderIdsBetween(any(), any())).thenReturn(List.of("RCH-1"));
        when(billProviderRegistry.fetchBill("WECHAT", date)).thenReturn(List.of(
                new PlatformBillLine("P-R1", "RCH-1", 2700, Instant.now(), "RECHARGE", null, "{}"),
                new PlatformBillLine("P-RF1", "RCH-1", -450, Instant.now(), "REFUND", null, "{}")
        ));
        when(reconRepository.save(any())).thenAnswer(inv -> {
            var r = inv.getArgument(0, com.aicabinet.trade.domain.PaymentReconciliation.class);
            if (r.getReconId() == null) {
                r.setReconId(13L);
            }
            return r;
        });

        var result = service.runDaily(100000001L, date, "WECHAT");

        assertEquals("MATCHED", result.status());
        assertEquals(2250, result.ledgerTotal());
        assertEquals(0, result.diffCents());
    }

    @Test
    void runDaily_recomputesExistingReconciliation() {
        LocalDate date = LocalDate.now();
        var existing = new com.aicabinet.trade.domain.PaymentReconciliation();
        existing.setReconId(9L);
        existing.setReconDate(date);
        existing.setChannel("MOCK");
        when(reconRepository.findByReconDateAndChannel(date, "MOCK")).thenReturn(Optional.of(existing));
        when(paymentOperationRepository.sumNetCashflowBetween(any(), any(), eq("MOCK"))).thenReturn(0L);
        when(rechargeRepository.sumPaidAmountBetween(any(), any())).thenReturn(0L);
        when(paymentOperationRepository.findDistinctCabinetOrderIdsBetween(any(), any(), eq("MOCK")))
                .thenReturn(List.of());
        when(rechargeRepository.findPaidOrderIdsBetween(any(), any())).thenReturn(List.of());
        when(billProviderRegistry.fetchBill("MOCK", date)).thenReturn(List.of());
        when(reconRepository.save(any())).thenAnswer(inv -> {
            var r = inv.getArgument(0, com.aicabinet.trade.domain.PaymentReconciliation.class);
            if (r.getReconId() == null) r.setReconId(10L);
            return r;
        });

        var result = service.runDaily(100000001L, date, "MOCK");

        assertEquals("MATCHED", result.status());
        verify(billLineRepository).deleteByReconId(9L);
        verify(reconRepository).delete(existing);
        verify(reconRepository).flush();
    }

    @Test
    void runDaily_feeAggregatedIntoRecon() {
        // CB-020③：微信账单行手续费 100+50=150 应聚合写入 recon.channelFeeCents，金额对平时 status 不受费率影响
        LocalDate date = LocalDate.of(2024, 6, 5);
        when(reconRepository.findByReconDateAndChannel(date, "WECHAT")).thenReturn(Optional.empty());
        when(paymentOperationRepository.sumNetCashflowBetween(any(), any(), eq("WECHAT"))).thenReturn(1000L);
        when(rechargeRepository.sumPaidAmountBetween(any(), any())).thenReturn(0L);
        when(paymentOperationRepository.findDistinctCabinetOrderIdsBetween(any(), any(), eq("WECHAT")))
                .thenReturn(List.of("ORD-1", "ORD-2"));
        when(rechargeRepository.findPaidOrderIdsBetween(any(), any())).thenReturn(List.of());
        when(billProviderRegistry.fetchBill("WECHAT", date)).thenReturn(List.of(
                new PlatformBillLine("P1", "ORD-1", 600, Instant.now(), "PAY", 100L, "{}"),
                new PlatformBillLine("P2", "ORD-2", 400, Instant.now(), "PAY", 50L, "{}")
        ));
        when(reconRepository.save(any())).thenAnswer(inv -> {
            var r = inv.getArgument(0, com.aicabinet.trade.domain.PaymentReconciliation.class);
            if (r.getReconId() == null) r.setReconId(20L);
            return r;
        });

        var result = service.runDaily(100000001L, date, "WECHAT");

        assertEquals("MATCHED", result.status());
        verify(reconRepository, atLeastOnce()).save(argThat(r ->
                Long.valueOf(150L).equals(r.getChannelFeeCents())));
        verify(billLineRepository, atLeastOnce()).save(argThat(line ->
                line.getFeeCents() != null && line.getFeeCents() == 100L));
    }

    @Test
    void runDaily_feeNotProvidedStoresNullNotZero() {
        // CB-020③：通道不提供手续费（Mock/支付宝未映射）⇒ 存 null（估算兜底信号），与「实结 0」严格区分
        LocalDate date = LocalDate.of(2024, 6, 6);
        when(reconRepository.findByReconDateAndChannel(date, "MOCK")).thenReturn(Optional.empty());
        when(paymentOperationRepository.sumNetCashflowBetween(any(), any(), eq("MOCK"))).thenReturn(350L);
        when(rechargeRepository.sumPaidAmountBetween(any(), any())).thenReturn(0L);
        when(paymentOperationRepository.findDistinctCabinetOrderIdsBetween(any(), any(), eq("MOCK")))
                .thenReturn(List.of("ORD-1"));
        when(rechargeRepository.findPaidOrderIdsBetween(any(), any())).thenReturn(List.of());
        when(billProviderRegistry.fetchBill("MOCK", date)).thenReturn(List.of(
                new PlatformBillLine("P1", "ORD-1", 350, Instant.now(), "PAY", null, "{}")
        ));
        when(reconRepository.save(any())).thenAnswer(inv -> {
            var r = inv.getArgument(0, com.aicabinet.trade.domain.PaymentReconciliation.class);
            if (r.getReconId() == null) r.setReconId(21L);
            return r;
        });

        var result = service.runDaily(100000001L, date, "MOCK");

        assertEquals("MATCHED", result.status());
        verify(reconRepository, atLeastOnce()).save(argThat(r -> r.getChannelFeeCents() == null));
    }

    @Test
    void runDaily_feeRateAnomalyAlertsButKeepsMatchedStatus() {
        // CB-020③：实结费率 1000bps vs 配置 60bps——告警+指标，但 status 仍 MATCHED（MISMATCH 语义只留给金额/单据不平）
        LocalDate date = LocalDate.of(2024, 6, 7);
        when(systemConfigService.getInt("fund.channel_fee_bps", 60)).thenReturn(60);
        when(reconRepository.findByReconDateAndChannel(date, "WECHAT")).thenReturn(Optional.empty());
        when(paymentOperationRepository.sumNetCashflowBetween(any(), any(), eq("WECHAT"))).thenReturn(1000L);
        when(rechargeRepository.sumPaidAmountBetween(any(), any())).thenReturn(0L);
        when(paymentOperationRepository.findDistinctCabinetOrderIdsBetween(any(), any(), eq("WECHAT")))
                .thenReturn(List.of("ORD-1"));
        when(rechargeRepository.findPaidOrderIdsBetween(any(), any())).thenReturn(List.of());
        when(billProviderRegistry.fetchBill("WECHAT", date)).thenReturn(List.of(
                new PlatformBillLine("P1", "ORD-1", 1000, Instant.now(), "PAY", 100L, "{}")
        ));
        when(reconRepository.save(any())).thenAnswer(inv -> {
            var r = inv.getArgument(0, com.aicabinet.trade.domain.PaymentReconciliation.class);
            if (r.getReconId() == null) r.setReconId(22L);
            return r;
        });

        var result = service.runDaily(100000001L, date, "WECHAT");

        assertEquals("MATCHED", result.status());
        verify(cabinetMetrics, atLeastOnce()).recordReconciliationMismatch();
    }
}
