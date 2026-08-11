package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("device_temperature_reading")
@Getter
@Setter
public class DeviceTemperatureReading {

    @TableId(type = IdType.AUTO)
    private Long readingId;

    private String deviceId;

    private int tempC;

    private Instant reportedAt;

}
