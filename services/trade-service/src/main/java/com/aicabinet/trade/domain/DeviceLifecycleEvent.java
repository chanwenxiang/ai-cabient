package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("device_lifecycle_event")
@Getter
@Setter
public class DeviceLifecycleEvent {
    @TableId(type = IdType.AUTO)
    private Long eventId;
    private String deviceId;
    private String fromStatus;
    private String toStatus;
    private String action;
    private Long operatorId;
    private String remark;
    private Instant createdAt;

}
