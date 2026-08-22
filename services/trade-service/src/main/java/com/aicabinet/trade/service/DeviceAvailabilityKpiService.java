package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceAvailabilityKpiDto;
import com.aicabinet.trade.domain.DeviceAvailabilityKpiDaily;
import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.DeviceAvailabilityKpiDailyMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.OpsExceptionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public DeviceAvailabilityKpiService(DeviceInfoMapper deviceRepository,
                                        OpsExceptionMapper exceptionRepository,
                                        AdminAuditLogMapper auditRepository,
                                        DeviceAvailabilityKpiDailyMapper kpiRepository) {
        this.deviceRepository = deviceRepository;
        this.exceptionRepository = exceptionRepository;
        this.auditRepository = auditRepository;
        this.kpiRepository = kpiRepository;
    }

    @Transactional
    public DeviceAvailabilityKpiDto snapshotYesterday() {
        return snapshotDaily(LocalDate.now(ZONE).minusDays(1));
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
        DeviceAvailabilityKpiDaily row = computeRow(date);
        row.setCreatedAt(Instant.now());
        kpiRepository.save(row);
        log.info("device availability kpi snapshot date={} offline={} autoLock={} autoUnlock={} manualUnlock={}",
                date, row.getOfflineEvents(), row.getAutoLockCount(),
                row.getAutoUnlockCount(), row.getManualUnlockCount());
        return toDto(row);
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
