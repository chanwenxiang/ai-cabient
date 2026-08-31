package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.aicabinet.trade.config.JsonStringTypeHandler;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName(value = "dispute_ticket", autoResultMap = true)
@Getter
@Setter
public class DisputeTicket {

    @TableId(type = IdType.INPUT)
    private String ticketId;

    private String sessionId;

    private String reason;

    private String status;

    private String category = "RECOGNITION";

    private String priority = "NORMAL";

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String items;

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String resolutionItems;

    private Instant createdAt;

    private Instant resolvedAt;

    private Instant slaDueAt;

    private Instant slaReminderAt;

    private Instant slaAlertedAt;

    private String operatorNote;

    /** 处理人展示名（结案时写入，问责用） */
    private String assignee;

    private Instant closedAt;

    private Instant reopenedAt;

    /** LOW_CONF / EMPTY / UNMAPPED / NEED_REVIEW / WHITELIST */
    private String reviewCode;

    /** JSON array of vision detected class names (unmapped hints). */
    private String detectedClasses;

}
