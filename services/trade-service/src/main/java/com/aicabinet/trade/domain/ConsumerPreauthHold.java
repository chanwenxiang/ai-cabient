package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("consumer_preauth_hold")
@Getter
@Setter
public class ConsumerPreauthHold {

    @TableId
    private String sessionId;

    private Long userId;

    private int holdCents;

    private String status;

    private Instant createdAt;

    private Instant updatedAt;
}
