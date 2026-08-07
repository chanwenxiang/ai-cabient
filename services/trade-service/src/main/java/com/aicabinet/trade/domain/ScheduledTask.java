package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("scheduled_task")
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

    public String getTaskKey() { return taskKey; }
    public void setTaskKey(String taskKey) { this.taskKey = taskKey; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public String getTaskGroup() { return taskGroup; }
    public void setTaskGroup(String taskGroup) { this.taskGroup = taskGroup; }
    public String getScheduleDesc() { return scheduleDesc; }
    public void setScheduleDesc(String scheduleDesc) { this.scheduleDesc = scheduleDesc; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Instant getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Instant lastRunAt) { this.lastRunAt = lastRunAt; }
    public String getLastResult() { return lastResult; }
    public void setLastResult(String lastResult) { this.lastResult = lastResult; }
    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }
    public Long getLastDurationMs() { return lastDurationMs; }
    public void setLastDurationMs(Long lastDurationMs) { this.lastDurationMs = lastDurationMs; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
