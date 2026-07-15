package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("device_temperature_reading")
public class DeviceTemperatureReading {

    @TableId(type = IdType.AUTO)
    private Long readingId;

    private String deviceId;

    private int tempC;

    private Instant reportedAt;

public Long getReadingId() { return readingId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public int getTempC() { return tempC; }
    public void setTempC(int tempC) { this.tempC = tempC; }
    public Instant getReportedAt() { return reportedAt; }
    public void setReportedAt(Instant reportedAt) { this.reportedAt = reportedAt; }
}
