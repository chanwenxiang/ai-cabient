package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("device_ops_event")
@Getter
@Setter
public class DeviceOpsEvent {
    @TableId(type = IdType.AUTO)
    private Long eventId;
    private String deviceId;
    private String eventType;
    private String severity;
    private String title;
    private String detail;
    private Instant createdAt;

}
