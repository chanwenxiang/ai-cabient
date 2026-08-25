package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@TableName("ops_user_device_scope")
@Getter
@Setter
public class OpsUserDeviceScope {
    private Long userId;
    private String deviceId;

}
