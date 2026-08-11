package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.aicabinet.trade.config.JsonStringTypeHandler;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName(value = "risk_event", autoResultMap = true)
@Getter
@Setter
public class RiskEvent {

    @TableId(type = IdType.AUTO)
    private Long eventId;

    private Long userId;

    private String deviceId;

    private String eventType;

    private String severity = "WARN";

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String detail;

    private Instant createdAt;

}
