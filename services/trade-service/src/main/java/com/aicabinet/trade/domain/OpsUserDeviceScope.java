package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("ops_user_device_scope")
public class OpsUserDeviceScope {
    private Long userId;
    private String deviceId;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
}
