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

@Service
public class FinanceReportService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final CabinetOrderRepository orderRepository;
    private final CabinetOrderLineRepository lineRepository;
    private final InventoryWriteOffRepository writeOffRepository;

    public FinanceReportService(CabinetOrderRepository orderRepository,
                                CabinetOrderLineRepository lineRepository,
                                InventoryWriteOffRepository writeOffRepository) {
        this.orderRepository = orderRepository;
        this.lineRepository = lineRepository;
        this.writeOffRepository = writeOffRepository;
    }

    @Transactional(readOnly = true)
    public FinanceStatsDto stats() {
        Instant startOfDay = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        long revenueToday = orderRepository.sumTotalAmountSince(startOfDay);
        long cogsToday = lineRepository.sumCogsSince(startOfDay);
        long writeOffToday = writeOffRepository.sumCostCentsSince(startOfDay);
        long writeOffQty = writeOffRepository.sumQuantitySince(startOfDay);
        long revenueTotal = orderRepository.sumTotalAmount();
        long cogsTotal = lineRepository.sumCogsTotal();
        return new FinanceStatsDto(
                revenueToday,
                cogsToday,
                revenueToday - cogsToday,
                writeOffToday,
                writeOffQty,
                revenueTotal,
                cogsTotal,
                revenueTotal - cogsTotal
        );
    }

    @Transactional(readOnly = true)
    public FinanceReportDto report(int days) {
        int window = Math.min(Math.max(days, 1), 30);
        FinanceStatsDto summary = stats();
        List<FinanceDailyDto> daily = new ArrayList<>();
        LocalDate today = LocalDate.now(ZONE);
        for (int i = window - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            Instant start = day.atStartOfDay(ZONE).toInstant();
            Instant end = day.plusDays(1).atStartOfDay(ZONE).toInstant();
            long revenue = orderRepository.sumTotalAmountBetween(start, end);
            long cogs = lineRepository.sumCogsBetween(start, end);
            long writeOff = writeOffRepository.sumCostCentsBetween(start, end);
            daily.add(new FinanceDailyDto(
                    day.toString(),
                    revenue,
                    cogs,
                    revenue - cogs,
                    writeOff
            ));
        }
        Instant sinceSkus = today.minusDays(window - 1L).atStartOfDay(ZONE).toInstant();
        List<FinanceSkuDto> topSkus = lineRepository.skuBreakdownSince(sinceSkus).stream()
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
}
