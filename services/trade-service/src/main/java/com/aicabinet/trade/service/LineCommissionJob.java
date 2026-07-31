package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.LineCommissionDaily;
import com.aicabinet.trade.domain.LineDevice;
import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.LineCommissionDailyMapper;
import com.aicabinet.trade.mapper.LineDeviceMapper;
import com.aicabinet.trade.mapper.LineManagerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

@Service
public class LineCommissionJob {

    private static final Logger log = LoggerFactory.getLogger(LineCommissionJob.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> PAID_STATUSES = Set.of("PAID", "COMPLETED");

    private final LineManagerMapper managerMapper;
    private final LineDeviceMapper deviceMapper;
    private final CabinetOrderMapper orderMapper;
    private final LineCommissionDailyMapper commissionDailyMapper;
    private final LineWalletService lineWalletService;

    public LineCommissionJob(LineManagerMapper managerMapper,
                             LineDeviceMapper deviceMapper,
                             CabinetOrderMapper orderMapper,
                             LineCommissionDailyMapper commissionDailyMapper,
                             LineWalletService lineWalletService) {
        this.managerMapper = managerMapper;
        this.deviceMapper = deviceMapper;
        this.orderMapper = orderMapper;
        this.commissionDailyMapper = commissionDailyMapper;
        this.lineWalletService = lineWalletService;
    }

    @Scheduled(cron = "0 20 0 * * *", zone = "Asia/Shanghai")
    @Transactional
    public void postDailyCommission() {
        LocalDate bizDate = LocalDate.now(ZONE).minusDays(1);
        Instant start = bizDate.atStartOfDay(ZONE).toInstant();
        Instant end = bizDate.plusDays(1).atStartOfDay(ZONE).toInstant();
        List<LineDevice> bindings = deviceMapper.findByStatus(LineManagerService.STATUS_ACTIVE);
        int posted = 0;
        for (LineDevice binding : bindings) {
            LineManager manager = managerMapper.findById(binding.getManagerId()).orElse(null);
            if (manager == null || !LineManagerService.STATUS_ACTIVE.equalsIgnoreCase(manager.getStatus())) {
                continue;
            }
            if (commissionDailyMapper.findByManagerIdAndBizDateAndDeviceId(
                    manager.getManagerId(), bizDate, binding.getDeviceId()).isPresent()) {
                continue;
            }
            List<CabinetOrder> orders = orderMapper.findByCreatedAtBetween(start, end).stream()
                    .filter(o -> binding.getDeviceId().equals(o.getDeviceId()))
                    .filter(o -> o.getStatus() != null && PAID_STATUSES.contains(o.getStatus().toUpperCase()))
                    .toList();
            if (orders.isEmpty()) {
                continue;
            }
            long gmv = orders.stream().mapToLong(CabinetOrder::getTotalAmountCents).sum();
            int rateBps = manager.getCommissionRateBps() == null ? 0 : manager.getCommissionRateBps();
            int fixed = manager.getCommissionFixedCents() == null ? 0 : manager.getCommissionFixedCents();
            long commission = gmv * rateBps / 10_000L + (long) fixed * orders.size();
            if (commission <= 0) {
                continue;
            }
            LineCommissionDaily row = new LineCommissionDaily();
            row.setManagerId(manager.getManagerId());
            row.setBizDate(bizDate);
            row.setDeviceId(binding.getDeviceId());
            row.setOrderCount(orders.size());
            row.setGmvCents(gmv);
            row.setCommissionCents(commission);
            row.setStatus("POSTED");
            row.setCreatedAt(Instant.now());
            commissionDailyMapper.insert(row);
            String refId = bizDate + "|" + binding.getDeviceId();
            lineWalletService.credit(manager.getManagerId(), commission, "COMMISSION",
                    "COMMISSION_DAILY", refId, "线长日佣金 " + bizDate + " " + binding.getDeviceId());
            posted++;
        }
        if (posted > 0) {
            log.info("Line commission posted for {} device-day rows on {}", posted, bizDate);
        }
    }
}
