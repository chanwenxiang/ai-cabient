package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.aicabinet.trade.config.JsonStringTypeHandler;
import java.time.Instant;

@TableName(value = "promotion_activity", autoResultMap = true)
public class PromotionActivity {

    @TableId(type = IdType.AUTO)
    private Long activityId;

    private String activityName;

    private String activityType;

    private String status = "DRAFT";

    private Instant startTime;

    private Instant endTime;

    private long budgetCents;

    private long usedCents;

    private int userLimit = 1;

    private String deviceScope = "ALL";

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String ruleConfig = "{}";

    private String description;

    private Long operatorId;

    private Instant createdAt;

    private Instant updatedAt;

public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public String getActivityName() { return activityName; }
    public void setActivityName(String activityName) { this.activityName = activityName; }
    public String getActivityType() { return activityType; }
    public void setActivityType(String activityType) { this.activityType = activityType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }
    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }
    public long getBudgetCents() { return budgetCents; }
    public void setBudgetCents(long budgetCents) { this.budgetCents = budgetCents; }
    public long getUsedCents() { return usedCents; }
    public void setUsedCents(long usedCents) { this.usedCents = usedCents; }
    public int getUserLimit() { return userLimit; }
    public void setUserLimit(int userLimit) { this.userLimit = userLimit; }
    public String getDeviceScope() { return deviceScope; }
    public void setDeviceScope(String deviceScope) { this.deviceScope = deviceScope; }
    public String getRuleConfig() { return ruleConfig; }
    public void setRuleConfig(String ruleConfig) { this.ruleConfig = ruleConfig; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
