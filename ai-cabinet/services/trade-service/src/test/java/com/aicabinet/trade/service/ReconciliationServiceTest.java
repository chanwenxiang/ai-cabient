package com.aicabinet.trade.service;

import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.reconciliation.PlatformBillLine;
import com.aicabinet.trade.reconciliation.PlatformBillProviderRegistry;
import com.aicabinet.trade.repository.CabinetOrderRepository;
import com.aicabinet.trade.repository.PaymentPlatformBillLineRepository;
import com.aicabinet.trade.repository.PaymentReconciliationRepository;
import com.aicabinet.trade.repository.RechargeOrderRepository;
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

    @Mock private PaymentReconciliationRepository reconRepository;
    @Mock private PaymentPlatformBillLineRepository billLineRepository;
    @Mock private CabinetOrderRepository orderRepository;
    @Mock private RechargeOrderRepository rechargeRepository;
    @Mock private PlatformBillProviderRegistry billProviderRegistry;
    @Mock private CabinetMetrics cabinetMetrics;

    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new ReconciliationService(
                reconRepository, billLineRepository, orderRepository, rechargeRepository,
                billProviderRegistry, new ObjectMapper(), cabinetMetrics);
    }

    @Test
    void runDaily_matchedWhenPlatformEqualsLedger() {
        LocalDate date = LocalDate.of(2024, 6, 1);
        ZoneId zone = ZoneId.systemDefault();
        Instant start = date.atStartOfDay(zone).toInstant();

        when(reconRepository.findByReconDateAndChannel(date, "MOCK")).thenReturn(Optional.empty());
        when(orderRepository.sumTotalAmountBetween(any(), any())).thenReturn(350L);
        when(rechargeRepository.sumPaidAmountBetween(any(), any())).thenReturn(0L);
        when(orderRepository.findOrderIdsBetween(any(), any())).thenReturn(List.of("ORD-1"));
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
        when(orderRepository.sumTotalAmountBetween(any(), any())).thenReturn(100L);
        when(rechargeRepository.sumPaidAmountBetween(any(), any())).thenReturn(0L);
        when(orderRepository.findOrderIdsBetween(any(), any())).thenReturn(List.of());
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
}
