package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SlaMetricsDto;
import com.aicabinet.common.dto.SlaRealtimeDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.SlaDailySnapshot;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.mapper.SlaDailySnapshotMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

@Service
public class SlaMetricsService {

    private static final List<SessionState> DOOR_SUCCESS_STATES =
            List.of(SessionState.COMPLETED, SessionState.DISPUTED);

    private final ShoppingSessionMapper sessionRepository;
    private final DeviceInfoMapper deviceRepository;
    private final SlaDailySnapshotMapper snapshotRepository;
    private final MerchantScopeService merchantScopeService;
    private final DisputeTicketMapper disputeRepository;
    private final DisputeSlaService disputeSlaService;

    @Autowired
    private ScheduledTaskService taskService;

    public SlaMetricsService(ShoppingSessionMapper sessionRepository,
                             DeviceInfoMapper deviceRepository,
                             SlaDailySnapshotMapper snapshotRepository,
                             MerchantScopeService merchantScopeService,
                             DisputeTicketMapper disputeRepository,
                             DisputeSlaService disputeSlaService) {
        this.sessionRepository = sessionRepository;
        this.deviceRepository = deviceRepository;
        this.snapshotRepository = snapshotRepository;
        this.merchantScopeService = merchantScopeService;
        this.disputeRepository = disputeRepository;
        this.disputeSlaService = disputeSlaService;
    }

    @Transactional(readOnly = true)
    public SlaMetricsDto current(Long operatorId) {
        SlaRealtimeDto realtime = realtimeMetrics(operatorId);
        int currentOnline = (int) deviceRepository.countByOnlineStatus("ONLINE");
        int deviceTotal = (int) deviceRepository.count();
        return snapshotRepository.findFirstByOrderBySnapshotDateDesc()
                .map(s -> {
                    int snapPeak = s.getDeviceOnlinePeak();
                    // OBS-011：峰值至少不低于当前在线，避免快照 0 与在线率矛盾
                    int peak = Math.max(snapPeak, currentOnline);
                    int total = s.getDeviceTotal() > 0 ? s.getDeviceTotal() : deviceTotal;
                    return new SlaMetricsDto(
                            s.getSnapshotDate(),
                            s.getDoorOpenAttempts(),
                            s.getDoorOpenSuccess(),
                            s.getDoorSuccessRate() != null ? s.getDoorSuccessRate() : 0,
                            s.getAvgRecognizeMs() != null ? s.getAvgRecognizeMs() : 0,
                            s.getP95RecognizeMs() != null ? s.getP95RecognizeMs() : 0,
                            total,
                            peak,
                            s.getDeviceOnlineRate() != null ? s.getDeviceOnlineRate() : 0,
                            realtime
                    );
                })
                .orElseGet(() -> new SlaMetricsDto(
                        // 无快照时 KPI 卡用 0/0 + 成功率 0，峰值用当前在线
                        LocalDate.now(), 0, 0, 0f, 0, 0,
                        deviceTotal, currentOnline, realtime.deviceOnlineRateNow(), realtime
                ));
    }

    @Scheduled(cron = "0 5 0 * * *")
    @Transactional
    public void snapshotDaily() {
        long start = System.nanoTime();
        if (!taskService.tryBegin("sla-snapshot", 600)) {
            return;
        }
        boolean failed = false;
        try {
            LocalDate yesterday = LocalDate.now().minusDays(1);
            SlaDailySnapshot snap = buildSnapshot(yesterday);
            snapshotRepository.save(snap);
        } catch (Exception e) {
            failed = true;
            taskService.finish("sla-snapshot", "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish("sla-snapshot", "SUCCESS", null, start);
            }
        }
    }

    @Transactional
    public SlaDailySnapshot buildSnapshot(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();

        int attempts = (int) sessionRepository.countCreatedBetween(start, end);
        int success = (int) sessionRepository.countCreatedBetweenAndStateIn(start, end, DOOR_SUCCESS_STATES);
        long avgMs = nz(sessionRepository.avgDoorOpenMsBetween(start, end));
        long p95 = nz(sessionRepository.p95DoorOpenMsBetween(start, end));

        int deviceTotal = (int) deviceRepository.count();
        int online = (int) deviceRepository.countByOnlineStatus("ONLINE");

        SlaDailySnapshot snap = new SlaDailySnapshot();
        snap.setSnapshotDate(date);
        snap.setDoorOpenAttempts(attempts);
        snap.setDoorOpenSuccess(success);
        snap.setDoorSuccessRate(attempts > 0 ? (float) success / attempts : 0f);
        snap.setAvgRecognizeMs(avgMs);
        snap.setP95RecognizeMs(p95);
        snap.setDeviceTotal(deviceTotal);
        snap.setDeviceOnlinePeak(online);
        snap.setDeviceOnlineRate(deviceTotal == 0 ? 0f : (float) online / deviceTotal);
        return snap;
    }

    @Transactional(readOnly = true)
    public SlaRealtimeDto realtimeMetrics() {
        return computeRealtime(null, null);
    }

    @Transactional(readOnly = true)
    public SlaRealtimeDto realtimeMetrics(Long operatorId) {
        Set<String> scopedDevices = merchantScopeService.allowedDeviceIds(operatorId);
        if (scopedDevices != null && scopedDevices.isEmpty()) {
            return new SlaRealtimeDto(0.0, 0, 0, 0, 0, 0, 0.0);
        }
        List<DeviceInfo> devices = scopedDevices == null
                ? null
                : merchantScopeService.allowedDevices(operatorId);
        return computeRealtime(scopedDevices, devices);
    }

    private SlaRealtimeDto computeRealtime(Set<String> scopedDevices, List<DeviceInfo> scopedDeviceList) {
        Instant since24h = Instant.now().minus(24, ChronoUnit.HOURS);

        long attempts;
        long success;
        long avg;
        if (scopedDevices == null) {
            attempts = sessionRepository.countByCreatedAtAfter(since24h);
            success = sessionRepository.countCreatedAfterAndStateIn(since24h, DOOR_SUCCESS_STATES);
            avg = nz(sessionRepository.avgDoorOpenMsCreatedAfter(since24h));
        } else {
            attempts = sessionRepository.countByDeviceIdInAndCreatedAtAfter(scopedDevices, since24h);
            success = sessionRepository.countCreatedAfterAndStateInForDevices(
                    since24h, DOOR_SUCCESS_STATES, scopedDevices);
            avg = nz(sessionRepository.avgDoorOpenMsCreatedAfterForDevices(since24h, scopedDevices));
        }

        // OBS-011：无开门尝试时返回 0，避免 0/0 被展示成 100%
        double doorRate = attempts > 0 ? (double) success / attempts : 0.0;

        long online;
        long totalDevices;
        if (scopedDeviceList != null) {
            totalDevices = scopedDeviceList.size();
            online = scopedDeviceList.stream()
                    .filter(d -> "ONLINE".equalsIgnoreCase(d.getOnlineStatus()))
                    .count();
        } else {
            totalDevices = deviceRepository.count();
            online = deviceRepository.countByOnlineStatus("ONLINE");
        }
        double onlineRate = totalDevices == 0 ? 0 : (double) online / totalDevices;

        long disputeOpen = disputeRepository.countByStatus("OPEN");
        long disputeOverdue = disputeSlaService.countOverdue();
        long disputeResolved24h = disputeRepository.countResolvedSince(since24h);
        double disputeSlaCompliance = disputeSlaService.slaComplianceRate24h();

        return new SlaRealtimeDto(doorRate, avg, onlineRate, disputeOpen, disputeOverdue,
                disputeResolved24h, disputeSlaCompliance);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
