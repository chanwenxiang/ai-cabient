package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("notification_template")
@Getter
@Setter
public class NotificationTemplate {

    @TableId(type = IdType.AUTO)
    private Long templateId;

    private String templateCode;

    private String templateName;

    private String channel = "IN_APP";

    private String channels = "IN_APP";

    private String category;

    private String titleTemplate;

    private String bodyTemplate;

    private String audience = "CONSUMER";

    private String status = "ACTIVE";

    private Instant createdAt = Instant.now();

    private Instant updatedAt;

}
