package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceEnvReadingDto;
import com.aicabinet.trade.domain.DeviceEnvReading;
import com.aicabinet.trade.mapper.DeviceEnvReadingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public DeviceEnvService(DeviceEnvReadingMapper readingRepository) {
        this.readingRepository = readingRepository;
    }

    @Transactional
    public void record(String deviceId, Double humidityPct, Double voltageV, Double powerW) {
        recordIfPresent(deviceId, METRIC_HUMIDITY, humidityPct);
        recordIfPresent(deviceId, METRIC_VOLTAGE, voltageV);
        recordIfPresent(deviceId, METRIC_POWER, powerW);
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
