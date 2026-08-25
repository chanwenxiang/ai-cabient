package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("scheduled_task")
@Getter
@Setter
public class ScheduledTask {

    @TableId(type = IdType.INPUT)
    private String taskKey;

    private String taskName;
    private String taskGroup;
    private String scheduleDesc;
    private Boolean enabled;
    private Instant lastRunAt;
    private String lastResult;
    private String lastMessage;
    private Long lastDurationMs;
    private String remark;
    private Instant updatedAt;

}
