package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "promotion_activity")
public class PromotionActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long activityId;

    @Column(nullable = false, length = 128)
    private String activityName;

    @Column(nullable = false, length = 32)
    private String activityType;

    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    @Column(nullable = false)
    private Instant startTime;

    @Column(nullable = false)
    private Instant endTime;

    @Column(nullable = false)
    private long budgetCents;

    @Column(nullable = false)
    private long usedCents;

    @Column(nullable = false)
    private int userLimit = 1;

    @Column(nullable = false, length = 32)
    private String deviceScope = "ALL";

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "rule_config", columnDefinition = "jsonb")
    private String ruleConfig = "{}";

    @Column(columnDefinition = "TEXT")
    private String description;

    private Long operatorId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

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
