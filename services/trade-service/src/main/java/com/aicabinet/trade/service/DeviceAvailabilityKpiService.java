package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceAvailabilityKpiDto;
import com.aicabinet.trade.domain.DeviceAvailabilityKpiDaily;
import com.aicabinet.trade.domain.DeviceDailyOnlineRate;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.OpsException;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.DeviceAvailabilityKpiDailyMapper;
import com.aicabinet.trade.mapper.DeviceDailyOnlineRateMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备可用性 KPI 日快照：离线事件数、自动锁机/解锁数、人工解锁占比、
 * 锁机平均时长与离线平均恢复时长。
 * <p>由 XXL-JOB 任务 deviceAvailabilityKpiDailyJob 每日统计前一天。</p>
 * <p>CB-018 ②（2026-10-09）：新增柜机×日在线率快照（{@link #snapshotDeviceOnlineRates}），
 * 数据源是 DEVICE_OFFLINE 异常区间——心跳只 UPDATE 当前状态三字段，不可回溯。</p>
 */
@Service
public class DeviceAvailabilityKpiService {

    private static final Logger log = LoggerFactory.getLogger(DeviceAvailabilityKpiService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String OFFLINE_TYPE = "DEVICE_OFFLINE";

    private final DeviceInfoMapper deviceRepository;
    private final OpsExceptionMapper exceptionRepository;
    private final AdminAuditLogMapper auditRepository;
    private final DeviceAvailabilityKpiDailyMapper kpiRepository;
    private final DeviceDailyOnlineRateMapper onlineRateRepository;
    private final DistributedLockService distributedLockService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final DeviceAvailabilityKpiService self;

    public DeviceAvailabilityKpiService(DeviceInfoMapper deviceRepository,
                                        OpsExceptionMapper exceptionRepository,
                                        AdminAuditLogMapper auditRepository,
                                        DeviceAvailabilityKpiDailyMapper kpiRepository,
                                        DeviceDailyOnlineRateMapper onlineRateRepository,
                                        DistributedLockService distributedLockService, @Lazy DeviceAvailabilityKpiService self) {
        this.deviceRepository = deviceRepository;
        this.exceptionRepository = exceptionRepository;
        this.auditRepository = auditRepository;
        this.kpiRepository = kpiRepository;
        this.onlineRateRepository = onlineRateRepository;
        this.distributedLockService = distributedLockService;
        this.self = self;
    }

    @Transactional
    public DeviceAvailabilityKpiDto snapshotYesterday() {
        DeviceAvailabilityKpiDto dto = self.snapshotDaily(LocalDate.now(ZONE).minusDays(1));
        // CB-018 ②：柜机×日在线率同窗快照。独立 try：在线率是增强指标，失败只记错误，
        // 不拖垮既有可用性 KPI 快照（两者写不同表，无一致性耦合）。
        LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);
        try {
            self.snapshotDeviceOnlineRates(yesterday);
        } catch (Exception e) {
            log.error("device online rate snapshot failed date={}", yesterday, e);
        }
        return dto;
    }

    /** 默认口径：当天实时 KPI（不落库，随业务实时变化）。 */
    @Transactional(readOnly = true)
    public DeviceAvailabilityKpiDto today() {
        return toDto(computeRow(LocalDate.now(ZONE)));
    }

    /** 指定日期：已有日快照返回快照（终值），无快照则按当天口径实时计算。 */
    @Transactional(readOnly = true)
    public DeviceAvailabilityKpiDto getByDate(LocalDate date) {
        DeviceAvailabilityKpiDaily existing = kpiRepository.selectById(date);
        return existing != null ? toDto(existing) : toDto(computeRow(date));
    }

    @Transactional
    public DeviceAvailabilityKpiDto snapshotDaily(LocalDate date) {
        if (!distributedLockService.tryLock(deviceKpiDailyLockKey(date), 60, 5)) {
            log.warn("device kpi snapshot lock busy date={}", date);
            return self.getByDate(date);
        }
        try {
            return doSnapshotDaily(date);
        } finally {
            distributedLockService.unlock(deviceKpiDailyLockKey(date));
        }
    }

    static String deviceKpiDailyLockKey(LocalDate date) {
        return "device-kpi:daily:" + date;
    }

    static String deviceOnlineRateLockKey(LocalDate date) {
        return "device-online-rate:daily:" + date;
    }

    /**
     * CB-018 ②：柜机×日在线率快照（幂等，重跑整行覆盖）。
     * <p>分母 = device_info 全部已注册设备；分子扣减 = DEVICE_OFFLINE 区间与
     * [dayStart, windowEnd) 的相交分钟（向上取整）。进行中日 windowEnd=统计时刻，
     * 进行中的离线段自然被截断。未注册设备（registerUnknown 前无 deviceId）不计入。</p>
     *
     * @return 写入行数（= 已注册设备数）
     */
    @Transactional
    public int snapshotDeviceOnlineRates(LocalDate date) {
        if (!distributedLockService.tryLock(deviceOnlineRateLockKey(date), 60, 5)) {
            log.warn("device online rate snapshot lock busy date={}", date);
            return 0;
        }
        try {
            return doSnapshotDeviceOnlineRates(date);
        } finally {
            distributedLockService.unlock(deviceOnlineRateLockKey(date));
        }
    }

    private int doSnapshotDeviceOnlineRates(LocalDate date) {
        Instant dayStart = date.atStartOfDay(ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(ZONE).toInstant();
        Instant now = Instant.now();
        Instant windowEnd = dayEnd.isAfter(now) ? now : dayEnd;
        long totalMinutes = Math.max(0, Duration.between(dayStart, windowEnd).toMinutes());

        Map<String, Long> offlineByDevice = new LinkedHashMap<>();
        if (totalMinutes > 0) {
            for (OpsException seg : exceptionRepository.findOfflineSegmentsOverlapping(dayStart, windowEnd)) {
                String deviceId = seg.getDeviceId();
                if (deviceId == null || deviceId.isBlank()) {
                    continue;
                }
                long minutes = clipOfflineMinutes(seg.getCreatedAt(),
                        seg.getResolvedAt() == null ? now : seg.getResolvedAt(), dayStart, windowEnd);
                if (minutes > 0) {
                    offlineByDevice.merge(deviceId, minutes, Long::sum);
                }
            }
        }
        var devices = deviceRepository.selectList(
                Wrappers.<DeviceInfo>lambdaQuery().select(DeviceInfo::getDeviceId));
        Instant computedAt = Instant.now();
        int written = 0;
        for (DeviceInfo d : devices) {
            String deviceId = d.getDeviceId();
            if (deviceId == null || deviceId.isBlank()) {
                continue;
            }
            long offline = Math.min(offlineByDevice.getOrDefault(deviceId, 0L), totalMinutes);
            DeviceDailyOnlineRate row = new DeviceDailyOnlineRate();
            row.setKpiDate(date);
            row.setDeviceId(deviceId);
            row.setOfflineMinutes((int) offline);
            row.setOnlineMinutes((int) Math.max(0, totalMinutes - offline));
            row.setRate(totalMinutes > 0
                    ? Math.max(0d, Math.min(1d, (totalMinutes - offline) / (double) totalMinutes))
                    : 0d);
            row.setComputedAt(computedAt);
            onlineRateRepository.upsert(row);
            written++;
        }
        log.info("device online rate snapshot date={} devices={} windowMinutes={}", date, written, totalMinutes);
        return written;
    }

    /**
     * 纯函数：离线段 [segStart, segEnd) 与日窗 [dayStart, dayEnd) 的相交分钟数。
     * 秒累计向上取整到分钟（离线多算保守口径）。包级可见供纯逻辑单测直接钉住。
     */
    static long clipOfflineMinutes(Instant segStart, Instant segEnd, Instant dayStart, Instant dayEnd) {
        Instant s = segStart.isBefore(dayStart) ? dayStart : segStart;
        Instant e = segEnd.isAfter(dayEnd) ? dayEnd : segEnd;
        if (!e.isAfter(s)) {
            return 0;
        }
        long seconds = Duration.between(s, e).toSeconds();
        return (seconds + 59) / 60;
    }

    private DeviceAvailabilityKpiDto doSnapshotDaily(LocalDate date) {
        DeviceAvailabilityKpiDaily computed = computeRow(date);
        DeviceAvailabilityKpiDaily row = kpiRepository.findByIdForUpdate(date).orElseGet(() -> {
            DeviceAvailabilityKpiDaily fresh = new DeviceAvailabilityKpiDaily();
            fresh.setKpiDate(date);
            return fresh;
        });
        copyMetrics(computed, row);
        row.setCreatedAt(Instant.now());
        if (row.getKpiDate() == null) {
            row.setKpiDate(date);
        }
        if (kpiRepository.selectById(date) == null) {
            kpiRepository.insert(row);
        } else {
            kpiRepository.updateById(row);
        }
        log.info("device availability kpi snapshot date={} offline={} autoLock={} autoUnlock={} manualUnlock={}",
                date, row.getOfflineEvents(), row.getAutoLockCount(),
                row.getAutoUnlockCount(), row.getManualUnlockCount());
        return toDto(row);
    }

    private static void copyMetrics(DeviceAvailabilityKpiDaily from, DeviceAvailabilityKpiDaily to) {
        to.setDeviceTotal(from.getDeviceTotal());
        to.setOfflineEvents(from.getOfflineEvents());
        to.setAutoLockCount(from.getAutoLockCount());
        to.setAutoUnlockCount(from.getAutoUnlockCount());
        to.setManualUnlockCount(from.getManualUnlockCount());
        to.setAvgLockHours(from.getAvgLockHours());
        to.setAvgRecoverHours(from.getAvgRecoverHours());
        to.setManualInterventionRate(from.getManualInterventionRate());
    }

    private DeviceAvailabilityKpiDaily computeRow(LocalDate date) {
        Instant start = date.atStartOfDay(ZONE).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(ZONE).toInstant();

        DeviceAvailabilityKpiDaily row = new DeviceAvailabilityKpiDaily();
        row.setKpiDate(date);
        row.setDeviceTotal((int) deviceRepository.count());
        row.setOfflineEvents((int) exceptionRepository
                .countByExceptionTypeAndCreatedAtBetween("DEVICE_OFFLINE", start, end));
        row.setAutoLockCount((int) exceptionRepository
                .countByExceptionTypeAndCreatedAtBetween("DEVICE_FAULT", start, end));
        row.setAutoUnlockCount((int) auditRepository
                .countByActionAndCreatedAtBetween("DEVICE_AUTO_UNLOCK_STABLE_ONLINE", start, end));
        row.setManualUnlockCount((int) auditRepository
                .countByActionAndOperatorIdNotAndCreatedAtBetween("DEVICE_UNLOCK", 0L, start, end));
        row.setAvgLockHours(exceptionRepository
                .avgResolutionHoursByExceptionTypeAndCreatedAtBetween("DEVICE_FAULT", start, end));
        row.setAvgRecoverHours(exceptionRepository
                .avgResolutionHoursByExceptionTypeAndCreatedAtBetween("DEVICE_OFFLINE", start, end));

        int auto = row.getAutoUnlockCount() == null ? 0 : row.getAutoUnlockCount();
        int manual = row.getManualUnlockCount() == null ? 0 : row.getManualUnlockCount();
        // 无解锁样本时记 0，避免前端出现空值/破折号；有样本则人工占比
        row.setManualInterventionRate(auto + manual > 0 ? (double) manual / (auto + manual) : 0d);
        return row;
    }

    private DeviceAvailabilityKpiDto toDto(DeviceAvailabilityKpiDaily row) {
        return new DeviceAvailabilityKpiDto(
                row.getKpiDate(),
                nz(row.getDeviceTotal()),
                nz(row.getOfflineEvents()),
                nz(row.getAutoLockCount()),
                nz(row.getAutoUnlockCount()),
                nz(row.getManualUnlockCount()),
                row.getAvgLockHours(),
                row.getAvgRecoverHours(),
                row.getManualInterventionRate());
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
