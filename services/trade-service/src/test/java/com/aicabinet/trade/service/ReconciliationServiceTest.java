package com.aicabinet.trade.service;

import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.reconciliation.PlatformBillLine;
import com.aicabinet.trade.reconciliation.PlatformBillProviderRegistry;
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

    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new ReconciliationService(
                reconRepository, billLineRepository, paymentOperationRepository, rechargeRepository,
                billProviderRegistry, new ObjectMapper(), cabinetMetrics, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        org.mockito.Mockito.lenient().when(distributedLockService.tryLock(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
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
                new PlatformBillLine("P1", "ORD-1", 350, start, "PAY", "{}")
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
                new PlatformBillLine("P9", "ORD-X", 500, Instant.now(), "WECHAT", "raw")
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
                new PlatformBillLine("P1", "ORD-1", 200, Instant.now(), "PAY", "{}"),
                new PlatformBillLine("P2", "RCH-1", 150, Instant.now(), "PAY", "{}")
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
}
