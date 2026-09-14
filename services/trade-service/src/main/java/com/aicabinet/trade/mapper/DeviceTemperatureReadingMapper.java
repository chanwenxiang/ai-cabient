package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceTemperatureReading;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DeviceTemperatureReadingMapper extends BaseTradeMapper<DeviceTemperatureReading> {

    /** 默认历史点上限：约 1 点/分钟 × 24h，防 168h 窗口无界膨胀。 */
    int DEFAULT_HISTORY_LIMIT = 2000;

    List<DeviceTemperatureReading> findByDeviceIdSince(
            @Param("deviceId") String deviceId,
            @Param("since") Instant since,
            @Param("limit") int limit);

    default List<DeviceTemperatureReading> findByDeviceIdSince(String deviceId, Instant since) {
        return findByDeviceIdSince(deviceId, since, DEFAULT_HISTORY_LIMIT);
    }
}
