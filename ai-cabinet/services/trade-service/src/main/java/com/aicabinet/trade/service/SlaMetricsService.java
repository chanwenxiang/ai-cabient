package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SlaMetricsDto;
import com.aicabinet.common.dto.SlaRealtimeDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.SlaDailySnapshot;
import com.aicabinet.trade.repository.DeviceInfoRepository;
import com.aicabinet.trade.repository.ShoppingSessionRepository;
import com.aicabinet.trade.repository.SlaDailySnapshotRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class SlaMetricsService {

    private final ShoppingSessionRepository sessionRepository;
    private final DeviceInfoRepository deviceRepository;
    private final SlaDailySnapshotRepository snapshotRepository;

    public SlaMetricsService(ShoppingSessionRepository sessionRepository,
                             DeviceInfoRepository deviceRepository,
                             SlaDailySnapshotRepository snapshotRepository) {
        this.sessionRepository = sessionRepository;
        this.deviceRepository = deviceRepository;
        this.snapshotRepository = snapshotRepository;
    }

    @Transactional(readOnly = true)
    public SlaMetricsDto current(Long operatorId) {
        SlaRealtimeDto realtime = computeRealtime();
        return snapshotRepository.findFirstByOrderBySnapshotDateDesc()
                .map(s -> new SlaMetricsDto(
                        s.getSnapshotDate(),
                        s.getDoorOpenAttempts(),
                        s.getDoorOpenSuccess(),
                        s.getDoorSuccessRate() != null ? s.getDoorSuccessRate() : 0,
                        s.getAvgRecognizeMs() != null ? s.getAvgRecognizeMs() : 0,
                        s.getP95RecognizeMs() != null ? s.getP95RecognizeMs() : 0,
                        s.getDeviceTotal(),
                        s.getDeviceOnlinePeak(),
                        s.getDeviceOnlineRate() != null ? s.getDeviceOnlineRate() : 0,
                        realtime
                ))
                .orElseGet(() -> new SlaMetricsDto(
                        LocalDate.now(), 0, 0, realtime.doorSuccessRate24h(), 0, 0,
                        (int) deviceRepository.count(), 0, realtime.deviceOnlineRateNow(), realtime
                ));
    }

    @Scheduled(cron = "0 5 0 * * *")
    @Transactional
    public void snapshotDaily() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        SlaDailySnapshot snap = buildSnapshot(yesterday);
        snapshotRepository.save(snap);
    }

    @Transactional
    public SlaDailySnapshot buildSnapshot(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();

        List<ShoppingSession> sessions = sessionRepository.findAll().stream()
                .filter(s -> s.getCreatedAt() != null
                        && !s.getCreatedAt().isBefore(start)
                        && s.getCreatedAt().isBefore(end))
                .toList();

        int attempts = sessions.size();
        int success = (int) sessions.stream()
                .filter(s -> s.getState() == SessionState.COMPLETED || s.getState() == SessionState.DISPUTED)
                .count();

        List<Long> recognizeMs = sessions.stream()
                .filter(s -> s.getOpenTime() != null && s.getCloseTime() != null)
                .map(s -> ChronoUnit.MILLIS.between(s.getOpenTime(), s.getCloseTime()))
                .sorted()
                .toList();

        long avgMs = recognizeMs.isEmpty() ? 0
                : recognizeMs.stream().mapToLong(Long::longValue).sum() / recognizeMs.size();
        long p95 = recognizeMs.isEmpty() ? 0
                : recognizeMs.get((int) (recognizeMs.size() * 0.95) - Math.max(1, 0));

        List<DeviceInfo> devices = deviceRepository.findAll();
        int online = (int) devices.stream()
                .filter(d -> "ONLINE".equalsIgnoreCase(d.getOnlineStatus()))
                .count();

        SlaDailySnapshot snap = new SlaDailySnapshot();
        snap.setSnapshotDate(date);
        snap.setDoorOpenAttempts(attempts);
        snap.setDoorOpenSuccess(success);
        snap.setDoorSuccessRate(attempts > 0 ? (float) success / attempts : 0f);
        snap.setAvgRecognizeMs(avgMs);
        snap.setP95RecognizeMs(p95);
        snap.setDeviceTotal(devices.size());
        snap.setDeviceOnlinePeak(online);
        snap.setDeviceOnlineRate(devices.isEmpty() ? 0f : (float) online / devices.size());
        return snap;
    }

    private SlaRealtimeDto computeRealtime() {
        Instant since24h = Instant.now().minus(24, ChronoUnit.HOURS);
        long attempts = sessionRepository.countByCreatedAtAfter(since24h);
        long success = sessionRepository.findAll().stream()
                .filter(s -> s.getCreatedAt() != null && s.getCreatedAt().isAfter(since24h))
                .filter(s -> s.getState() == SessionState.COMPLETED || s.getState() == SessionState.DISPUTED)
                .count();

        double doorRate = attempts > 0 ? (double) success / attempts : 1.0;

        List<Long> ms = sessionRepository.findAll().stream()
                .filter(s -> s.getCreatedAt() != null && s.getCreatedAt().isAfter(since24h))
                .filter(s -> s.getOpenTime() != null && s.getCloseTime() != null)
                .map(s -> ChronoUnit.MILLIS.between(s.getOpenTime(), s.getCloseTime()))
                .toList();
        long avg = ms.isEmpty() ? 0 : ms.stream().mapToLong(Long::longValue).sum() / ms.size();

        List<DeviceInfo> devices = deviceRepository.findAll();
        long online = devices.stream().filter(d -> "ONLINE".equalsIgnoreCase(d.getOnlineStatus())).count();
        double onlineRate = devices.isEmpty() ? 0 : (double) online / devices.size();

        return new SlaRealtimeDto(doorRate, avg, onlineRate);
    }
}
