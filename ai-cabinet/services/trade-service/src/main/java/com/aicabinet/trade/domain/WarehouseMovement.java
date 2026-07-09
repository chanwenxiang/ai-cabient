package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "warehouse_movement")
public class WarehouseMovement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long movementId;
    @Column(nullable = false, length = 32)
    private String warehouseId;
    @Column(nullable = false, length = 64)
    private String skuId;
    @Column(length = 64)
    private String batchNo;
    @Column(nullable = false, length = 32)
    private String movementType;
    @Column(nullable = false)
    private int deltaQty;
    @Column(length = 32)
    private String refType;
    @Column(length = 64)
    private String refId;
    private Long operatorId;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getMovementId() { return movementId; }
    public String getWarehouseId() { return warehouseId; }
    public void setWarehouseId(String warehouseId) { this.warehouseId = warehouseId; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getMovementType() { return movementType; }
    public void setMovementType(String movementType) { this.movementType = movementType; }
    public int getDeltaQty() { return deltaQty; }
    public void setDeltaQty(int deltaQty) { this.deltaQty = deltaQty; }
    public String getRefType() { return refType; }
    public void setRefType(String refType) { this.refType = refType; }
    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public Instant getCreatedAt() { return createdAt; }
}
