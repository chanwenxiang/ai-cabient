package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("coupon_definition")
public class CouponDefinition {

    @TableId(type = IdType.AUTO)
    private Long couponDefId;

    private String couponName;

    private String couponType;

    private int denominationCents;

    private int minSpendCents;

    private Integer discountPercent;

    private int validityDays = 30;

    private int maxIssueCount;

    private int issuedCount;

    private Long activityId;

    private String deviceScope = "ALL";

    private String status = "ACTIVE";

    private String description;

    private Instant createdAt;

    private Instant updatedAt;

public Long getCouponDefId() { return couponDefId; }
    public void setCouponDefId(Long v) { this.couponDefId = v; }
    public String getCouponName() { return couponName; }
    public void setCouponName(String v) { this.couponName = v; }
    public String getCouponType() { return couponType; }
    public void setCouponType(String v) { this.couponType = v; }
    public int getDenominationCents() { return denominationCents; }
    public void setDenominationCents(int v) { this.denominationCents = v; }
    public int getMinSpendCents() { return minSpendCents; }
    public void setMinSpendCents(int v) { this.minSpendCents = v; }
    public Integer getDiscountPercent() { return discountPercent; }
    public void setDiscountPercent(Integer v) { this.discountPercent = v; }
    public int getValidityDays() { return validityDays; }
    public void setValidityDays(int v) { this.validityDays = v; }
    public int getMaxIssueCount() { return maxIssueCount; }
    public void setMaxIssueCount(int v) { this.maxIssueCount = v; }
    public int getIssuedCount() { return issuedCount; }
    public void setIssuedCount(int v) { this.issuedCount = v; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long v) { this.activityId = v; }
    public String getDeviceScope() { return deviceScope; }
    public void setDeviceScope(String v) { this.deviceScope = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
