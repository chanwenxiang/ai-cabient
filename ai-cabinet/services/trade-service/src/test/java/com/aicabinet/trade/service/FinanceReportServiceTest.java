package com.aicabinet.trade.service;

import com.aicabinet.common.dto.FinanceStatsDto;
import com.aicabinet.trade.repository.CabinetOrderLineRepository;
import com.aicabinet.trade.repository.CabinetOrderRepository;
import com.aicabinet.trade.repository.InventoryWriteOffRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinanceReportServiceTest {

    @Test
    void stats_scopesRevenueAndCostsToAllowedDevices() {
        CabinetOrderRepository orders = mock(CabinetOrderRepository.class);
        CabinetOrderLineRepository lines = mock(CabinetOrderLineRepository.class);
        InventoryWriteOffRepository writeOffs = mock(InventoryWriteOffRepository.class);
        MerchantScopeService scope = mock(MerchantScopeService.class);
        Set<String> devices = Set.of("CAB-1");
        when(scope.allowedDeviceIds(7L)).thenReturn(devices);
        when(orders.sumTotalAmountByDeviceIdInSince(any(), any())).thenReturn(1_000L);
        when(lines.sumCogsByDeviceIdsSince(any(), any())).thenReturn(600L, 6_000L);
        when(writeOffs.sumCostCentsByDeviceIdsSince(any(), any())).thenReturn(50L);
        when(writeOffs.sumQuantityByDeviceIdsSince(any(), any())).thenReturn(1L);
        when(orders.countByDeviceIdInAndCreatedAtAfter(any(), any())).thenReturn(4L);
        when(orders.sumTotalAmountByDeviceIdIn(devices)).thenReturn(10_000L);

        FinanceStatsDto result = new FinanceReportService(orders, lines, writeOffs, scope).stats(7L);

        assertEquals(1_000L, result.revenueTodayCents());
        assertEquals(400L, result.grossMarginTodayCents());
        assertEquals(250L, result.averageOrderValueTodayCents());
        assertEquals(0.4, result.grossMarginRateToday(), 0.0001);
        verify(orders, never()).sumTotalAmountSince(any(Instant.class));
    }

    @Test
    void stats_returnsZerosWithoutQueryingGlobalDataWhenScopeIsEmpty() {
        CabinetOrderRepository orders = mock(CabinetOrderRepository.class);
        CabinetOrderLineRepository lines = mock(CabinetOrderLineRepository.class);
        InventoryWriteOffRepository writeOffs = mock(InventoryWriteOffRepository.class);
        MerchantScopeService scope = mock(MerchantScopeService.class);
        when(scope.allowedDeviceIds(8L)).thenReturn(Set.of());

        FinanceStatsDto result = new FinanceReportService(orders, lines, writeOffs, scope).stats(8L);

        assertEquals(0L, result.revenueTodayCents());
        assertEquals(0L, result.orderToday());
        verify(orders, never()).sumTotalAmount();
    }
}
