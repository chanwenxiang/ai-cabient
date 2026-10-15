package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 设备 HTTP 独立凭据（审计 A1/P0-2）：设备可达内部端点（OTA check/progress、
 * screen-content、ad-play、sessions/video*）一机一密；deviceId 与凭据查库绑定。
 * 明文 secret 仅签发响应一次性返回，本表只存 sha256（与 V290 MQTT 凭据差异点：
 * 无需明文列——HTTP 校验在 trade 侧直接比 hash，不经 EMQX bootstrap）。
 */
@Getter
@Setter
@TableName("device_http_credential")
public class DeviceHttpCredential {
    @TableId(type = IdType.INPUT)
    private String deviceId;

    private String secretSha256;
    private String status;
    private Long issuedBy;
    private Instant issuedAt;
    private Instant rotatedAt;
    private Instant revokedAt;
    private String revokeReason;
}
