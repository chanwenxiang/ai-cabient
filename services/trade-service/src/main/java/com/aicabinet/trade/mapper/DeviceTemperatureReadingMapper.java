package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceTemperatureReading;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DeviceTemperatureReadingMapper extends BaseTradeMapper<DeviceTemperatureReading> {

        List<DeviceTemperatureReading> findByDeviceIdSince( @Param("deviceId") String deviceId, @Param("since") Instant since);

}
