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
    public void saveReading(String deviceId, Double humidityPct, Double voltageV, Double powerW) {
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

    /** MA-120：各读数的物理合理区间；越界值是固件异常读数，钳进区间防止污染环境看板。 */
    private static final java.util.Map<String, double[]> ENV_BOUNDS = java.util.Map.of(
            METRIC_HUMIDITY, new double[]{0, 100},
            METRIC_VOLTAGE, new double[]{0, 1000},
            METRIC_POWER, new double[]{0, 100_000});

    private void recordIfPresent(String deviceId, String metricType, Double value) {
        if (deviceId == null || deviceId.isBlank() || value == null) {
            return;
        }
        // MA-120：NaN/Infinity 会让 BigDecimal.valueOf 抛异常 → 心跳 500（且发生在 presence 更新之后）
        if (!Double.isFinite(value)) {
            return;
        }
        double bounded = value;
        double[] bounds = ENV_BOUNDS.get(metricType);
        if (bounds != null) {
            bounded = Math.max(bounds[0], Math.min(bounds[1], value));
        }
        DeviceEnvReading row = new DeviceEnvReading();
        row.setDeviceId(deviceId);
        row.setMetricType(metricType);
        row.setValue(BigDecimal.valueOf(bounded).setScale(2, java.math.RoundingMode.HALF_UP));
        row.setReportedAt(Instant.now());
        readingRepository.insert(row);
    }
}
