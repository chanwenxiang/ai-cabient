package com.aicabinet.trade.service;

import com.aicabinet.common.dto.*;
import com.aicabinet.trade.repository.CabinetOrderLineRepository;
import com.aicabinet.trade.repository.CabinetOrderRepository;
import com.aicabinet.trade.repository.InventoryWriteOffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class FinanceReportService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final CabinetOrderRepository orderRepository;
    private final CabinetOrderLineRepository lineRepository;
    private final InventoryWriteOffRepository writeOffRepository;
    private final MerchantScopeService merchantScopeService;

    public FinanceReportService(CabinetOrderRepository orderRepository,
                                CabinetOrderLineRepository lineRepository,
                                InventoryWriteOffRepository writeOffRepository,
                                MerchantScopeService merchantScopeService) {
        this.orderRepository = orderRepository;
        this.lineRepository = lineRepository;
        this.writeOffRepository = writeOffRepository;
        this.merchantScopeService = merchantScopeService;
    }

    @Transactional(readOnly = true)
    public FinanceStatsDto stats(Long operatorId) {
        Instant startOfDay = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        Set<String> deviceIds = merchantScopeService.allowedDeviceIds(operatorId);
        if (deviceIds != null && deviceIds.isEmpty()) {
            return emptyStats();
        }
        long revenueToday = deviceIds == null
                ? orderRepository.sumTotalAmountSince(startOfDay)
                : orderRepository.sumTotalAmountByDeviceIdInSince(deviceIds, startOfDay);
        long cogsToday = deviceIds == null
                ? lineRepository.sumCogsSince(startOfDay)
                : lineRepository.sumCogsByDeviceIdsSince(deviceIds, startOfDay);
        long writeOffToday = deviceIds == null
                ? writeOffRepository.sumCostCentsSince(startOfDay)
                : writeOffRepository.sumCostCentsByDeviceIdsSince(deviceIds, startOfDay);
        long writeOffQty = deviceIds == null
                ? writeOffRepository.sumQuantitySince(startOfDay)
                : writeOffRepository.sumQuantityByDeviceIdsSince(deviceIds, startOfDay);
        long orderToday = deviceIds == null
                ? orderRepository.countByCreatedAtAfter(startOfDay)
                : orderRepository.countByDeviceIdInAndCreatedAtAfter(deviceIds, startOfDay);
        long revenueTotal = deviceIds == null
                ? orderRepository.sumTotalAmount()
                : orderRepository.sumTotalAmountByDeviceIdIn(deviceIds);
        long cogsTotal = deviceIds == null
                ? lineRepository.sumCogsTotal()
                : lineRepository.sumCogsByDeviceIdsSince(deviceIds, Instant.EPOCH);
        long grossMarginToday = revenueToday - cogsToday;
        return new FinanceStatsDto(
                revenueToday,
                cogsToday,
                grossMarginToday,
                writeOffToday,
                writeOffQty,
                orderToday,
                orderToday > 0 ? revenueToday / orderToday : 0,
                revenueToday > 0 ? (double) grossMarginToday / revenueToday : 0.0,
                revenueTotal,
                cogsTotal,
                revenueTotal - cogsTotal
        );
    }

    @Transactional(readOnly = true)
    public FinanceReportDto report(Long operatorId, int days) {
        int window = Math.min(Math.max(days, 1), 30);
        FinanceStatsDto summary = stats(operatorId);
        Set<String> deviceIds = merchantScopeService.allowedDeviceIds(operatorId);
        List<FinanceDailyDto> daily = new ArrayList<>();
        LocalDate today = LocalDate.now(ZONE);
        for (int i = window - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            Instant start = day.atStartOfDay(ZONE).toInstant();
            Instant end = day.plusDays(1).atStartOfDay(ZONE).toInstant();
            long revenue = deviceIds == null
                    ? orderRepository.sumTotalAmountBetween(start, end)
                    : deviceIds.isEmpty() ? 0 : orderRepository.sumTotalAmountByDeviceIdInBetween(deviceIds, start, end);
            long cogs = deviceIds == null
                    ? lineRepository.sumCogsBetween(start, end)
                    : deviceIds.isEmpty() ? 0 : lineRepository.sumCogsByDeviceIdsBetween(deviceIds, start, end);
            long writeOff = deviceIds == null
                    ? writeOffRepository.sumCostCentsBetween(start, end)
                    : deviceIds.isEmpty() ? 0 : writeOffRepository.sumCostCentsByDeviceIdsBetween(deviceIds, start, end);
            daily.add(new FinanceDailyDto(
                    day.toString(),
                    revenue,
                    cogs,
                    revenue - cogs,
                    writeOff
            ));
        }
        Instant sinceSkus = today.minusDays(window - 1L).atStartOfDay(ZONE).toInstant();
        List<Object[]> skuRows = deviceIds == null
                ? lineRepository.skuBreakdownSince(sinceSkus)
                : deviceIds.isEmpty() ? List.of() : lineRepository.skuBreakdownByDevicesSince(deviceIds, sinceSkus);
        List<FinanceSkuDto> topSkus = skuRows.stream()
                .limit(20)
                .map(row -> {
                    long qty = ((Number) row[2]).longValue();
                    long revenue = ((Number) row[3]).longValue();
                    long cogs = ((Number) row[4]).longValue();
                    return new FinanceSkuDto(
                            (String) row[0],
                            row[1] != null ? (String) row[1] : (String) row[0],
                            qty,
                            revenue,
                            cogs,
                            revenue - cogs
                    );
                })
                .toList();
        return new FinanceReportDto(summary, daily, topSkus);
    }

    private static FinanceStatsDto emptyStats() {
        return new FinanceStatsDto(0, 0, 0, 0, 0, 0, 0, 0.0, 0, 0, 0);
    }
}
