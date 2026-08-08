package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceTemperatureReading;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceTemperatureReadingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class DevicePresenceService {

    private static final Logger log = LoggerFactory.getLogger(DevicePresenceService.class);
    private static final long OFFLINE_AFTER_MINUTES = 2;

    private final DeviceInfoMapper deviceRepository;
    private final DeviceTemperatureReadingMapper temperatureReadingRepository;
    private final CabinetMetrics cabinetMetrics;
    private final OpsExceptionService opsExceptionService;
    private final SystemConfigService systemConfigService;
    private final AdminAuditService auditService;

    @Autowired
    private ScheduledTaskService taskService;

    public DevicePresenceService(DeviceInfoMapper deviceRepository,
                                 DeviceTemperatureReadingMapper temperatureReadingRepository,
                                 CabinetMetrics cabinetMetrics,
                                 OpsExceptionService opsExceptionService,
                                 SystemConfigService systemConfigService,
                                 AdminAuditService auditService) {
        this.deviceRepository = deviceRepository;
        this.temperatureReadingRepository = temperatureReadingRepository;
        this.cabinetMetrics = cabinetMetrics;
        this.opsExceptionService = opsExceptionService;
        this.systemConfigService = systemConfigService;
        this.auditService = auditService;
    }

    @Transactional
    public void heartbeat(String deviceId) {
        heartbeat(deviceId, null, null);
    }

    @Transactional
    public void heartbeat(String deviceId, String appVersion, String firmwareVersion) {
        heartbeat(deviceId, appVersion, firmwareVersion, null);
    }

    @Transactional
    public void heartbeat(String deviceId, String appVersion, String firmwareVersion, Integer currentTempC) {
        DeviceInfo device = deviceRepository.findById(deviceId).orElseGet(() -> registerUnknown(deviceId));
        boolean wasOnline = "ONLINE".equalsIgnoreCase(device.getOnlineStatus());
        device.setOnlineStatus("ONLINE");
        if (!wasOnline || device.getOnlineSince() == null) {
            // 记录本次恢复在线的时间点（持续在线时保留原值，用于统计稳定在线时长）
            device.setOnlineSince(Instant.now());
        }
        if (appVersion != null && !appVersion.isBlank()) {
            device.setAppVersion(appVersion);
        }
        if (firmwareVersion != null && !firmwareVersion.isBlank()) {
            device.setFirmwareVersion(firmwareVersion);
        }
        if (currentTempC != null) {
            device.setCurrentTempC(currentTempC);
            device.setTempReportedAt(Instant.now());
            DeviceTemperatureReading reading = new DeviceTemperatureReading();
            reading.setDeviceId(deviceId);
            reading.setTempC(currentTempC);
            reading.setReportedAt(Instant.now());
            temperatureReadingRepository.save(reading);
        }
        // A heartbeat may carry the same status and versions as the previous one.
        // Explicitly update its timestamp so JPA still persists liveness.
        device.markHeartbeatReceived();
        deviceRepository.save(device);
        opsExceptionService.resolveSystem("DEVICE_OFFLINE", deviceId, "设备心跳恢复，已自动上线");
        cabinetMetrics.refreshDeviceGauges(deviceRepository);
        log.debug("device heartbeat device={} app={} temp={}", deviceId, appVersion, currentTempC);
    }

    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void markStaleDevicesOffline() {
        long start = System.nanoTime();
        if (!taskService.tryBegin("device-presence", 600)) {
            return;
        }
        boolean failed = false;
        try {
        Instant cutoff = Instant.now().minus(OFFLINE_AFTER_MINUTES, ChronoUnit.MINUTES);
        deviceRepository.findByOnlineStatusAndUpdatedAtBefore("ONLINE", cutoff).forEach(d -> {
            d.setOnlineStatus("OFFLINE");
            d.setOnlineSince(null);
            deviceRepository.save(d);
            deviceRepository.clearOnlineSince(d.getDeviceId());
            opsExceptionService.report("DEVICE_OFFLINE", "CRITICAL", d.getDeviceId(), null,
                    null, null, "设备离线", "连续 " + OFFLINE_AFTER_MINUTES + " 分钟未收到心跳");
            log.info("device marked offline device={}", d.getDeviceId());
        });
        autoLockLongOfflineDevices();
        cabinetMetrics.refreshDeviceGauges(deviceRepository);
        } catch (Exception e) {
            failed = true;
            taskService.finish("device-presence", "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish("device-presence", "SUCCESS", null, start);
            }
        }
    }

    /** 离线超过配置分钟数后自动锁机停售（需运营手动解锁）。 */
    private void autoLockLongOfflineDevices() {
        int lockAfterMinutes = systemConfigService.getInt(
                SystemConfigService.DEVICE_OFFLINE_AUTO_LOCK_MINUTES, 10);
        if (lockAfterMinutes <= 0) {
            return;
        }
        Instant lockCutoff = Instant.now().minus(lockAfterMinutes, ChronoUnit.MINUTES);
        deviceRepository.findByOnlineStatusAndUpdatedAtBeforeAndSalesLockedFalse("OFFLINE", lockCutoff)
                .forEach(d -> {
                    d.setSalesLocked(true);
                    deviceRepository.save(d);
                    opsExceptionService.report("DEVICE_FAULT", "HIGH", d.getDeviceId(), null,
                            null, null, "离线超时自动停售",
                            "设备离线超过 " + lockAfterMinutes + " 分钟，已自动锁机（故障码 OFFLINE_TIMEOUT）");
                    auditService.record(0L, "DEVICE_AUTO_LOCK_OFFLINE", "DEVICE", d.getDeviceId(),
                            "离线超过 " + lockAfterMinutes + " 分钟，已自动锁机停售");
                    log.info("device auto sales-locked after offline device={} minutes={}",
                            d.getDeviceId(), lockAfterMinutes);
                });
    }

    private DeviceInfo registerUnknown(String deviceId) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName(deviceId);
        device.setDeviceType("AI_CABINET_V1");
        device.setOnlineStatus("ONLINE");
        device.setOnlineSince(Instant.now());
        return deviceRepository.save(device);
    }
}
