package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "warehouse_in_transit")
public class WarehouseInTransit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long transitId;

    @Column(nullable = false)
    private Long outboundId;

    @Column(nullable = false, length = 64)
    private String deviceId;

    @Column(nullable = false, length = 64)
    private String skuId;

    @Column(nullable = false, length = 64)
    private String batchNo;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, length = 16)
    private String status = "IN_TRANSIT";

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant receivedAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public Long getTransitId() { return transitId; }
    public Long getOutboundId() { return outboundId; }
    public void setOutboundId(Long outboundId) { this.outboundId = outboundId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
}
