package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@TableName("device_mqtt_credential")
public class DeviceMqttCredential {
    @TableId(type = IdType.INPUT)
    private String deviceId;

    private String mqttUsername;
    private String mqttSecret;
    private String secretSha256;
    private String status;
    private Long issuedBy;
    private Instant issuedAt;
    private Instant rotatedAt;
    private Instant revokedAt;
    private String revokeReason;
}
