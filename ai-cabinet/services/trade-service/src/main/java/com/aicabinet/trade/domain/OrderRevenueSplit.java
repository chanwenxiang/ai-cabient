package com.aicabinet.trade.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "order_revenue_split")
public class OrderRevenueSplit {

    @Id
    @Column(length = 32)
    private String splitId;

    @Column(nullable = false, length = 32)
    private String orderId;

    @Column(nullable = false, length = 32)
    private String merchantId;

    @Column(nullable = false, length = 64)
    private String deviceId;

    @Column(nullable = false)
    private long grossCents;

    @Column(nullable = false)
    private long platformCents;

    @Column(nullable = false)
    private long merchantCents;

    @Column(nullable = false, length = 16)
    private String status = "ACCRUED";

    @Column(length = 64)
    private String wechatOutOrderNo;

    @Column(length = 64)
    private String wechatTransactionId;

    @Column(length = 512)
    private String failureReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public String getSplitId() { return splitId; }
    public void setSplitId(String splitId) { this.splitId = splitId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public long getGrossCents() { return grossCents; }
    public void setGrossCents(long grossCents) { this.grossCents = grossCents; }
    public long getPlatformCents() { return platformCents; }
    public void setPlatformCents(long platformCents) { this.platformCents = platformCents; }
    public long getMerchantCents() { return merchantCents; }
    public void setMerchantCents(long merchantCents) { this.merchantCents = merchantCents; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getWechatOutOrderNo() { return wechatOutOrderNo; }
    public void setWechatOutOrderNo(String wechatOutOrderNo) { this.wechatOutOrderNo = wechatOutOrderNo; }
    public String getWechatTransactionId() { return wechatTransactionId; }
    public void setWechatTransactionId(String wechatTransactionId) { this.wechatTransactionId = wechatTransactionId; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public Instant getCreatedAt() { return createdAt; }
}
