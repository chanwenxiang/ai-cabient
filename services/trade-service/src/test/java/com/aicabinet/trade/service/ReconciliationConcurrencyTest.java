package com.aicabinet.trade.service;

import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.PaymentPlatformBillLineMapper;
import com.aicabinet.trade.mapper.PaymentReconciliationMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.aicabinet.trade.reconciliation.PlatformBillProviderRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationConcurrencyTest {

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
                billProviderRegistry, new ObjectMapper(), cabinetMetrics, distributedLockService);
    }

    @Test
    void runDaily_whenLockBusy_rejectsWithConflict() {
        LocalDate date = LocalDate.of(2024, 6, 1);
        when(distributedLockService.tryLock(
                eq(ReconciliationService.dailyReconciliationLockKey(date, "WECHAT")), eq(120L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.runDaily(10001L, date, "WECHAT"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void runDaily_whenNoExistingRecon_unlocksAfterCompletion() {
        LocalDate date = LocalDate.of(2024, 6, 2);
        when(distributedLockService.tryLock(
                eq(ReconciliationService.dailyReconciliationLockKey(date, "MOCK")), eq(120L), eq(5L)))
                .thenReturn(true);
        when(reconRepository.findByReconDateAndChannel(date, "MOCK")).thenReturn(java.util.Optional.empty());
        when(paymentOperationRepository.sumNetCashflowBetween(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), eq("MOCK")))
                .thenReturn(0L);
        when(rechargeRepository.sumPaidAmountBetween(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(0L);
        when(paymentOperationRepository.findDistinctCabinetOrderIdsBetween(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), eq("MOCK")))
                .thenReturn(java.util.List.of());
        when(rechargeRepository.findPaidOrderIdsBetween(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
        when(billProviderRegistry.fetchBill("MOCK", date)).thenReturn(java.util.List.of());
        when(reconRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            var r = inv.getArgument(0, com.aicabinet.trade.domain.PaymentReconciliation.class);
            if (r.getReconId() == null) {
                r.setReconId(1L);
            }
            return r;
        });

        service.runDaily(10001L, date, "MOCK");

        verify(distributedLockService).unlock(ReconciliationService.dailyReconciliationLockKey(date, "MOCK"));
    }
}
