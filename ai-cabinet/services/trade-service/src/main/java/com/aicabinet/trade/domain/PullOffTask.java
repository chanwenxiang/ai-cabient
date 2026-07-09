package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "pull_off_task")
public class PullOffTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long taskId;

    @Column(nullable = false, length = 64)
    private String deviceId;

    @Column(nullable = false, length = 64)
    private String skuId;

    @Column(length = 32)
    private String lotId;

    @Column(length = 64)
    private String batchNo;

    @Column(nullable = false)
    private int quantity = 1;

    @Column(nullable = false, length = 32)
    private String reason;

    @Column(nullable = false, length = 16)
    private String status = "OPEN";

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant resolvedAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public Long getTaskId() { return taskId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public String getLotId() { return lotId; }
    public void setLotId(String lotId) { this.lotId = lotId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
