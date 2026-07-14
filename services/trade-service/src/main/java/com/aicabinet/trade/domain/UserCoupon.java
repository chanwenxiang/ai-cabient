package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_coupon")
public class UserCoupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long couponId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long couponDefId;

    @Column(nullable = false, length = 32, unique = true)
    private String couponCode;

    @Column(nullable = false, length = 16)
    private String status = "UNUSED";

    @Column(nullable = false)
    private Instant receivedAt;

    private Instant usedAt;

    @Column(nullable = false)
    private Instant expireAt;

    @Column(length = 32)
    private String orderId;

    @Column(length = 64)
    private String deviceId;

    private Integer discountCents;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() { receivedAt = Instant.now(); createdAt = Instant.now(); }

    public Long getCouponId() { return couponId; }
    public void setCouponId(Long v) { this.couponId = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public Long getCouponDefId() { return couponDefId; }
    public void setCouponDefId(Long v) { this.couponDefId = v; }
    public String getCouponCode() { return couponCode; }
    public void setCouponCode(String v) { this.couponCode = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant v) { this.receivedAt = v; }
    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant v) { this.usedAt = v; }
    public Instant getExpireAt() { return expireAt; }
    public void setExpireAt(Instant v) { this.expireAt = v; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String v) { this.orderId = v; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String v) { this.deviceId = v; }
    public Integer getDiscountCents() { return discountCents; }
    public void setDiscountCents(Integer v) { this.discountCents = v; }
    public Instant getCreatedAt() { return createdAt; }
}
