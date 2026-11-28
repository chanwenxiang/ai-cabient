package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("notification_dispatch_outbox")
@Getter
@Setter
public class NotificationDispatchOutbox {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String idempotencyKey;

    private String payloadJson;

    private String status;

    private int retryCount;

    private Instant nextRetryAt;

    private String lastError;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant sentAt;
}
