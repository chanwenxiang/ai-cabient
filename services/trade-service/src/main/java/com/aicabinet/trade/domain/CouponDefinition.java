package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "coupon_definition")
public class CouponDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long couponDefId;

    @Column(nullable = false, length = 128)
    private String couponName;

    @Column(nullable = false, length = 32)
    private String couponType;

    @Column(nullable = false)
    private int denominationCents;

    @Column(nullable = false)
    private int minSpendCents;

    private Integer discountPercent;

    @Column(nullable = false)
    private int validityDays = 30;

    @Column(nullable = false)
    private int maxIssueCount;

    @Column(nullable = false)
    private int issuedCount;

    private Long activityId;

    @Column(nullable = false, length = 32)
    private String deviceScope = "ALL";

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() { createdAt = Instant.now(); updatedAt = Instant.now(); }
    @PreUpdate
    protected void onUpdate() { updatedAt = Instant.now(); }

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
