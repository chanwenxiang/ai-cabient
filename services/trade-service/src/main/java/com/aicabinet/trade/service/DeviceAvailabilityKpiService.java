package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceAvailabilityKpiDto;
import com.aicabinet.trade.domain.DeviceAvailabilityKpiDaily;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.DeviceAvailabilityKpiDailyMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 设备可用性 KPI 日快照：离线事件数、自动锁机/解锁数、人工解锁占比、
 * 锁机平均时长与离线平均恢复时长。
 * <p>由 XXL-JOB 任务 deviceAvailabilityKpiDailyJob 每日统计前一天。</p>
 */
@Service
public class DeviceAvailabilityKpiService {

    private static final Logger log = LoggerFactory.getLogger(DeviceAvailabilityKpiService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final DeviceInfoMapper deviceRepository;
    private final OpsExceptionMapper exceptionRepository;
    private final AdminAuditLogMapper auditRepository;
    private final DeviceAvailabilityKpiDailyMapper kpiRepository;
    private final DistributedLockService distributedLockService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final DeviceAvailabilityKpiService self;

    public DeviceAvailabilityKpiService(DeviceInfoMapper deviceRepository,
                                        OpsExceptionMapper exceptionRepository,
                                        AdminAuditLogMapper auditRepository,
                                        DeviceAvailabilityKpiDailyMapper kpiRepository,
                                        DistributedLockService distributedLockService, @Lazy DeviceAvailabilityKpiService self) {
        this.deviceRepository = deviceRepository;
        this.exceptionRepository = exceptionRepository;
        this.auditRepository = auditRepository;
        this.kpiRepository = kpiRepository;
        this.distributedLockService = distributedLockService;
        this.self = self;
    }

    @Transactional
    public DeviceAvailabilityKpiDto snapshotYesterday() {
        return self.snapshotDaily(LocalDate.now(ZONE).minusDays(1));
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
