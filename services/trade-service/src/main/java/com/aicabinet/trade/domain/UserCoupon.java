package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("user_coupon")
public class UserCoupon {

    @TableId(type = IdType.AUTO)
    private Long couponId;

    private Long userId;

    private Long couponDefId;

    private String couponCode;

    private String status = "UNUSED";

    private Instant receivedAt;

    private Instant usedAt;

    private Instant expireAt;

    private String orderId;

    private String deviceId;

    private Integer discountCents;

    private Instant createdAt;

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
