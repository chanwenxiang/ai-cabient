package com.aicabinet.trade.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "merchant")
public class Merchant {

    @Id
    @Column(length = 32)
    private String merchantId;

    @Column(nullable = false, length = 128)
    private String merchantName;

    @Column(length = 32)
    private String contactPhone;

    @Column(nullable = false)
    private int platformRateBps = 1000;

    @Column(length = 64)
    private String wechatReceiverId;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(length = 256)
    private String remark;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public int getPlatformRateBps() { return platformRateBps; }
    public void setPlatformRateBps(int platformRateBps) { this.platformRateBps = platformRateBps; }
    public String getWechatReceiverId() { return wechatReceiverId; }
    public void setWechatReceiverId(String wechatReceiverId) { this.wechatReceiverId = wechatReceiverId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
