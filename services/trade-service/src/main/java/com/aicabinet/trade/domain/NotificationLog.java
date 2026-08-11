package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("notification_log")
@Getter
@Setter
public class NotificationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String templateCode;

    private String channel;

    private String audience;

    private Long userId;

    private String merchantId;

    private String title;

    private String body;

    private String bizType;

    private String bizId;

    private String status = "SENT";

    private Instant readAt;

    private Instant createdAt = Instant.now();

}
