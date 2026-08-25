package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceEnvReadingDto;
import com.aicabinet.trade.domain.DeviceEnvReading;
import com.aicabinet.trade.mapper.DeviceEnvReadingMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 环境多指标读数：湿度 / 电压 / 功耗（温度走 device_temperature_reading）。
 */
@Service
public class DeviceEnvService {

    public static final String METRIC_HUMIDITY = "HUMIDITY";
    public static final String METRIC_VOLTAGE = "VOLTAGE";
    public static final String METRIC_POWER = "POWER";

    private final DeviceEnvReadingMapper readingRepository;
    private final DistributedLockService distributedLockService;

    public DeviceEnvService(DeviceEnvReadingMapper readingRepository,
                            DistributedLockService distributedLockService) {
        this.readingRepository = readingRepository;
        this.distributedLockService = distributedLockService;
    }

    @Transactional
    public void record(String deviceId, Double humidityPct, Double voltageV, Double powerW) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        String id = deviceId.trim();
        runWithDeviceEnvLock(id, () -> {
            recordIfPresent(id, METRIC_HUMIDITY, humidityPct);
            recordIfPresent(id, METRIC_VOLTAGE, voltageV);
            recordIfPresent(id, METRIC_POWER, powerW);
            return null;
        });
    }

    static String deviceEnvLockKey(String deviceId) {
        return "device:env:" + deviceId.trim();
    }

    private <T> T runWithDeviceEnvLock(String deviceId, java.util.function.Supplier<T> action) {
        String key = deviceEnvLockKey(deviceId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备环境数据上报处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    @Transactional(readOnly = true)
    public List<DeviceEnvReadingDto> list(String deviceId, String metricType, int hours, int limit) {
        Instant since = Instant.now().minusSeconds(Math.max(1, Math.min(hours, 24 * 30)) * 3600L);
        String type = metricType == null || metricType.isBlank() ? null : metricType.trim().toUpperCase();
        return readingRepository.findSince(deviceId, type, since, limit).stream()
                .map(r -> new DeviceEnvReadingDto(
                        r.getDeviceId(), r.getMetricType(), r.getValue().doubleValue(), r.getReportedAt()))
                .toList();
    }

    private void recordIfPresent(String deviceId, String metricType, Double value) {
        if (deviceId == null || deviceId.isBlank() || value == null) {
            return;
        }
        DeviceEnvReading row = new DeviceEnvReading();
        row.setDeviceId(deviceId);
        row.setMetricType(metricType);
        row.setValue(BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP));
        row.setReportedAt(Instant.now());
        readingRepository.insert(row);
    }
}
