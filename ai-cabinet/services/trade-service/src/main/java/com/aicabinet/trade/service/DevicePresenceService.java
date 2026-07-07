package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.repository.DeviceInfoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class DevicePresenceService {

    private static final Logger log = LoggerFactory.getLogger(DevicePresenceService.class);
    private static final long OFFLINE_AFTER_MINUTES = 2;

    private final DeviceInfoRepository deviceRepository;
    private final CabinetMetrics cabinetMetrics;

    public DevicePresenceService(DeviceInfoRepository deviceRepository, CabinetMetrics cabinetMetrics) {
        this.deviceRepository = deviceRepository;
        this.cabinetMetrics = cabinetMetrics;
    }

    @Transactional
    public void heartbeat(String deviceId) {
        heartbeat(deviceId, null, null);
    }

    @Transactional
    public void heartbeat(String deviceId, String appVersion, String firmwareVersion) {
        DeviceInfo device = deviceRepository.findById(deviceId).orElseGet(() -> registerUnknown(deviceId));
        device.setOnlineStatus("ONLINE");
        if (appVersion != null && !appVersion.isBlank()) {
            device.setAppVersion(appVersion);
        }
        if (firmwareVersion != null && !firmwareVersion.isBlank()) {
            device.setFirmwareVersion(firmwareVersion);
        }
        deviceRepository.save(device);
        cabinetMetrics.refreshDeviceGauges(deviceRepository);
        log.debug("device heartbeat device={} app={}", deviceId, appVersion);
    }

    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void markStaleDevicesOffline() {
        Instant cutoff = Instant.now().minus(OFFLINE_AFTER_MINUTES, ChronoUnit.MINUTES);
        deviceRepository.findAll().stream()
                .filter(d -> "ONLINE".equalsIgnoreCase(d.getOnlineStatus()))
                .filter(d -> d.getUpdatedAt() == null || d.getUpdatedAt().isBefore(cutoff))
                .forEach(d -> {
                    d.setOnlineStatus("OFFLINE");
                    deviceRepository.save(d);
                    log.info("device marked offline device={}", d.getDeviceId());
                });
        cabinetMetrics.refreshDeviceGauges(deviceRepository);
    }

    private DeviceInfo registerUnknown(String deviceId) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(deviceId);
        device.setDeviceName(deviceId);
        device.setDeviceType("AI_CABINET_V1");
        device.setOnlineStatus("ONLINE");
        return deviceRepository.save(device);
    }
}
