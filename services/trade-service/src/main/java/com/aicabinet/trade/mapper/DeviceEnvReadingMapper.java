package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceEnvReading;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;

@Mapper
public interface DeviceEnvReadingMapper extends BaseTradeMapper<DeviceEnvReading> {

    default List<DeviceEnvReading> findSince(String deviceId, String metricType, Instant since, int limit) {
        int lim = Math.max(1, Math.min(limit, 500));
        return selectList(Wrappers.<DeviceEnvReading>lambdaQuery()
                .eq(DeviceEnvReading::getDeviceId, deviceId)
                .eq(metricType != null && !metricType.isBlank(), DeviceEnvReading::getMetricType, metricType)
                .ge(DeviceEnvReading::getReportedAt, since)
                .orderByDesc(DeviceEnvReading::getReportedAt)
                .last("LIMIT " + lim));
    }
}
