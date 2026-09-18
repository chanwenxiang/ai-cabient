package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.LineCommissionDaily;
import com.aicabinet.trade.domain.LineDevice;
import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.LineCommissionDailyMapper;
import com.aicabinet.trade.mapper.LineDeviceMapper;
import com.aicabinet.trade.mapper.LineManagerMapper;
import com.aicabinet.trade.support.ScheduleZones;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class LineCommissionJob {
    private static final String LINE_COMMISSION = "line-commission";
    /** 除昨天外回扫的天数窗口：抢锁失败/停机漏算的设备-日在窗口内自愈补算（H19）。 */
    private static final int BACKFILL_DAYS = 7;


    private static final Logger log = LoggerFactory.getLogger(LineCommissionJob.class);
    private static final Set<String> PAID_STATUSES = Set.of("PAID", "COMPLETED");

    private final LineManagerMapper managerMapper;
    private final LineDeviceMapper deviceMapper;
    private final CabinetOrderMapper orderMapper;
    private final LineCommissionDailyMapper commissionDailyMapper;
    private final LineWalletService lineWalletService;
    private final DistributedLockService distributedLockService;
    private final ScheduledTaskService taskService;

    public LineCommissionJob(LineManagerMapper managerMapper,
                             LineDeviceMapper deviceMapper,
                             CabinetOrderMapper orderMapper,
                             LineCommissionDailyMapper commissionDailyMapper,
                             LineWalletService lineWalletService,
                             DistributedLockService distributedLockService,
                             ScheduledTaskService taskService) {
        this.managerMapper = managerMapper;
        this.deviceMapper = deviceMapper;
        this.orderMapper = orderMapper;
        this.commissionDailyMapper = commissionDailyMapper;
        this.lineWalletService = lineWalletService;
        this.distributedLockService = distributedLockService;
        this.taskService = taskService;
    }

    @Scheduled(cron = "0 20 0 * * *", zone = "${aicabinet.schedule.zone:Asia/Shanghai}")
    @Transactional
    public void postDailyCommission() {
        long taskStart = System.nanoTime();
        if (!taskService.tryBegin(LINE_COMMISSION, 1800)) {
            return;
        }
        boolean failed = false;
        String summary = "本次无线长佣金入账";
        try {
            LocalDate today = LocalDate.now(ScheduleZones.ZONE);
            // 主算昨天 + 回扫最近 7 天：单设备-日抢锁失败仅跳过当天，下次运行自愈（H19）
            LinkedHashSet<LocalDate> bizDates = new LinkedHashSet<>();
            for (int i = 1; i <= BACKFILL_DAYS; i++) {
                bizDates.add(today.minusDays(i));
            }
            List<LineDevice> bindings = deviceMapper.findByStatus(LineManagerService.STATUS_ACTIVE);
            int posted = 0;
            for (LineDevice binding : bindings) {
                LineManager manager = managerMapper.findById(binding.getManagerId()).orElse(null);
                if (manager == null || !LineManagerService.STATUS_ACTIVE.equalsIgnoreCase(manager.getStatus())) {
                    continue;
                }
                for (LocalDate bizDate : bizDates) {
                    // 无锁预查重，避免已入账设备-日反复抢锁
                    if (commissionDailyMapper.findByManagerIdAndBizDateAndDeviceId(
                            manager.getManagerId(), bizDate, binding.getDeviceId()).isPresent()) {
                        continue;
                    }
                    if (tryPostCommissionForBinding(manager, binding, bizDate)) {
                        posted++;
                    }
                }
            }
            summary = posted <= 0
                    ? "本次无线长佣金入账（含回扫 " + BACKFILL_DAYS + " 天）"
                    : "入账线长佣金 " + posted + " 条（含回扫 " + BACKFILL_DAYS + " 天）";
            if (posted > 0) {
                log.info("Line commission posted for {} device-day rows (backfill window={}d)",
                        posted, BACKFILL_DAYS);
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish(LINE_COMMISSION, "FAILED", e.getMessage(), taskStart);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(LINE_COMMISSION, "SUCCESS", summary, taskStart);
            }
        }
    }

    static String lineCommissionDailyLockKey(long managerId, String deviceId, LocalDate bizDate) {
        return "line-commission:daily:" + managerId + ":" + deviceId + ":" + bizDate;
    }

    private boolean tryPostCommissionForBinding(LineManager manager, LineDevice binding, LocalDate bizDate) {
        Instant start = bizDate.atStartOfDay(ScheduleZones.ZONE).toInstant();
        Instant end = bizDate.plusDays(1).atStartOfDay(ScheduleZones.ZONE).toInstant();
        String lockKey = lineCommissionDailyLockKey(
                manager.getManagerId(), binding.getDeviceId(), bizDate);
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            log.warn("line commission lock busy manager={} device={} date={}",
                    manager.getManagerId(), binding.getDeviceId(), bizDate);
            return false;
        }
        try {
            if (commissionDailyMapper.findByManagerIdAndBizDateAndDeviceId(
                    manager.getManagerId(), bizDate, binding.getDeviceId()).isPresent()) {
                return false;
            }
            List<CabinetOrder> orders = orderMapper
                    .findByDeviceIdAndCreatedAtBetween(binding.getDeviceId(), start, end)
                    .stream()
                    .filter(o -> o.getStatus() != null && PAID_STATUSES.contains(o.getStatus().toUpperCase()))
                    .toList();
            if (orders.isEmpty()) {
                return false;
            }
            long gmv = orders.stream().mapToLong(CabinetOrder::getTotalAmountCents).sum();
            int rateBps = manager.getCommissionRateBps() == null ? 0 : manager.getCommissionRateBps();
            int fixed = manager.getCommissionFixedCents() == null ? 0 : manager.getCommissionFixedCents();
            long commission = gmv * rateBps / 10_000L + (long) fixed * orders.size();
            if (commission <= 0) {
                return false;
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
            return true;
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }
}
