package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceTemperatureReading;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceTemperatureReadingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;

@Service
public class DevicePresenceService {
    private static final String DEVICE_PRESENCE = "device-presence";


    private static final Logger log = LoggerFactory.getLogger(DevicePresenceService.class);
    private static final long OFFLINE_AFTER_MINUTES = 2;

    private final DeviceInfoMapper deviceRepository;
    private final DeviceTemperatureReadingMapper temperatureReadingRepository;
    private final CabinetMetrics cabinetMetrics;
    private final OpsExceptionService opsExceptionService;
    private final SystemConfigService systemConfigService;
    private final AdminAuditService auditService;
    private final DistributedLockService distributedLockService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final DevicePresenceService self;
    private final ScheduledTaskService taskService;

    public DevicePresenceService(DeviceInfoMapper deviceRepository,
                                 DeviceTemperatureReadingMapper temperatureReadingRepository,
                                 CabinetMetrics cabinetMetrics,
                                 OpsExceptionService opsExceptionService,
                                 SystemConfigService systemConfigService,
                                 AdminAuditService auditService,
                                 DistributedLockService distributedLockService,
                                 @Lazy DevicePresenceService self,
                                 ScheduledTaskService taskService) {
        this.deviceRepository = deviceRepository;
        this.temperatureReadingRepository = temperatureReadingRepository;
        this.cabinetMetrics = cabinetMetrics;
        this.opsExceptionService = opsExceptionService;
        this.systemConfigService = systemConfigService;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
        this.self = self;
        this.taskService = taskService;
    }

    @Transactional
    public void heartbeat(String deviceId) {
        self.heartbeat(deviceId, null, null);
    }

    @Transactional
    public void heartbeat(String deviceId, String appVersion, String firmwareVersion) {
        self.heartbeat(deviceId, appVersion, firmwareVersion, null);
    }

    @Transactional
    public void heartbeat(String deviceId, String appVersion, String firmwareVersion, Integer currentTempC) {
        runWithPresenceLock(deviceId, () -> {
            doHeartbeat(deviceId, appVersion, firmwareVersion, currentTempC);
            return null;
        });
    }

    private void doHeartbeat(String deviceId, String appVersion, String firmwareVersion, Integer currentTempC) {
        DeviceInfo device = deviceRepository.findByIdForUpdate(deviceId).orElseGet(() -> registerUnknown(deviceId));
        boolean wasOnline = CabinetConstants.DEVICE_ONLINE.equalsIgnoreCase(device.getOnlineStatus());
        device.setOnlineStatus(CabinetConstants.DEVICE_ONLINE);
        if (!wasOnline || device.getOnlineSince() == null) {
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
        if (!taskService.tryBegin(DEVICE_PRESENCE, 600)) {
            return;
        }
        boolean failed = false;
        String summary = "本次无设备状态变更";
        try {
        Instant cutoff = Instant.now().minus(OFFLINE_AFTER_MINUTES, ChronoUnit.MINUTES);
        var stale = deviceRepository.findByOnlineStatusAndUpdatedAtBefore(CabinetConstants.DEVICE_ONLINE, cutoff);
        stale.forEach(d -> runWithPresenceLock(d.getDeviceId(), () -> {
            markDeviceOffline(d.getDeviceId());
            return null;
        }));
        int locked = autoLockLongOfflineDevices();
        cabinetMetrics.refreshDeviceGauges(deviceRepository);
        summary = "标记离线 " + stale.size() + " 台，自动锁机 " + locked + " 台";
        } catch (Exception e) {
            failed = true;
            taskService.finish(DEVICE_PRESENCE, "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(DEVICE_PRESENCE, "SUCCESS", summary, start);
            }
        }
    }

    private void markDeviceOffline(String deviceId) {
        DeviceInfo d = deviceRepository.findByIdForUpdate(deviceId).orElse(null);
        if (d == null || !CabinetConstants.DEVICE_ONLINE.equalsIgnoreCase(d.getOnlineStatus())) {
            return;
        }
        d.setOnlineStatus("OFFLINE");
        d.setOnlineSince(null);
        deviceRepository.save(d);
        deviceRepository.clearOnlineSince(deviceId);
        opsExceptionService.report("DEVICE_OFFLINE", "CRITICAL", new OpsExceptionService.ExceptionReport.ExceptionRefs(deviceId, null, null, null), "设备离线", "连续 " + OFFLINE_AFTER_MINUTES + " 分钟未收到心跳");
        log.info("device marked offline device={}", deviceId);
    }

    /** 离线超过配置分钟数后自动锁机停售（需运营手动解锁）。返回本次锁机台数。 */
    private int autoLockLongOfflineDevices() {
        int lockAfterMinutes = systemConfigService.getInt(
                SystemConfigService.DEVICE_OFFLINE_AUTO_LOCK_MINUTES, 10);
        if (lockAfterMinutes <= 0) {
            return 0;
        }
        int graceMinutes = systemConfigService.getInt(
                SystemConfigService.DEVICE_OFFLINE_MANUAL_UNLOCK_GRACE_MINUTES, 45);
        Instant now = Instant.now();
        Instant lockCutoff = now.minus(lockAfterMinutes, ChronoUnit.MINUTES);
        Instant graceCutoff = graceMinutes > 0 ? now.minus(graceMinutes, ChronoUnit.MINUTES) : null;
        int locked = 0;
        for (DeviceInfo candidate : deviceRepository.findByOnlineStatusAndUpdatedAtBeforeAndSalesLockedFalse(
                "OFFLINE", lockCutoff)) {
            Boolean applied = runWithDeviceSalesLock(candidate.getDeviceId(), () -> {
                DeviceInfo d = deviceRepository.findByIdForUpdate(candidate.getDeviceId()).orElse(null);
                if (d == null || d.salesLockedEnabled()) {
                    return false;
                }
                if (graceCutoff != null
                        && d.getSalesUnlockedAt() != null
                        && d.getSalesUnlockedAt().isAfter(graceCutoff)) {
                    log.debug("skip auto-lock within unlock grace device={} unlockedAt={}",
                            d.getDeviceId(), d.getSalesUnlockedAt());
                    return false;
                }
                d.setSalesLocked(true);
                d.setSalesLockReason("离线超时自动停售（超 " + lockAfterMinutes + " 分钟）");
                d.setSalesUnlockedAt(null);
                deviceRepository.save(d);
                deviceRepository.clearSalesUnlockedAt(d.getDeviceId());
                opsExceptionService.report("DEVICE_FAULT", "HIGH", new OpsExceptionService.ExceptionReport.ExceptionRefs(d.getDeviceId(), null, null, null), "离线超时自动停售", "设备离线超过 " + lockAfterMinutes + " 分钟，已自动锁机（故障码：离线超时）");
                auditService.record(0L, "DEVICE_AUTO_LOCK_OFFLINE", "DEVICE", d.getDeviceId(),
                        "离线超过 " + lockAfterMinutes + " 分钟，已自动锁机停售");
                log.info("device auto sales-locked after offline device={} minutes={}",
                        d.getDeviceId(), lockAfterMinutes);
                return true;
            });
            if (Boolean.TRUE.equals(applied)) {
                locked++;
            }
        }
        return locked;
    }

    private DeviceInfo registerUnknown(String deviceId) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName(deviceId);
        device.setDeviceType("AI_CABINET_V1");
        device.setOnlineStatus(CabinetConstants.DEVICE_ONLINE);
        device.setOnlineSince(Instant.now());
        return deviceRepository.save(device);
    }

    static String devicePresenceLockKey(String deviceId) {
        return "device:presence:" + deviceId;
    }

    private <T> T runWithPresenceLock(String deviceId, Supplier<T> action) {
        return runWithLock(devicePresenceLockKey(deviceId), action);
    }

    private <T> T runWithDeviceSalesLock(String deviceId, Supplier<T> action) {
        return runWithLock(DeviceSalesLockService.deviceSalesLockKey(deviceId), action);
    }

    private <T> T runWithLock(String lockKey, Supplier<T> action) {
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备在线状态处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }
}
