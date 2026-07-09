package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "warehouse")
public class Warehouse {

    @Id
    @Column(length = 32)
    private String warehouseId;

    @Column(nullable = false, length = 128)
    private String warehouseName;

    @Column(length = 256)
    private String address;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public String getWarehouseId() { return warehouseId; }
    public void setWarehouseId(String warehouseId) { this.warehouseId = warehouseId; }
    public String getWarehouseName() { return warehouseName; }
    public void setWarehouseName(String warehouseName) { this.warehouseName = warehouseName; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
