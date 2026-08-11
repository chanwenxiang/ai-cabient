package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("dispute_message")
@Getter
@Setter
public class DisputeMessage {

    @TableId(type = IdType.AUTO)
    private Long messageId;

    private String ticketId;

    private String authorType;

    private Long authorId;

    private String body;

    private Instant createdAt;

}
