package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@TableName("device_env_reading")
@Getter
@Setter
public class DeviceEnvReading {

    @TableId(type = IdType.AUTO)
    private Long readingId;
    private String deviceId;
    private String metricType;
    private BigDecimal value;
    private Instant reportedAt;

}
