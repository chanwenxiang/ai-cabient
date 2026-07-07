package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "device_sku_inventory")
public class DeviceSkuInventory {

    @EmbeddedId
    private DeviceSkuInventoryId id;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false)
    private int lowThreshold = 2;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public DeviceSkuInventoryId getId() { return id; }
    public void setId(DeviceSkuInventoryId id) { this.id = id; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }
    public int getLowThreshold() { return lowThreshold; }
    public void setLowThreshold(int lowThreshold) { this.lowThreshold = lowThreshold; }
    public Instant getUpdatedAt() { return updatedAt; }
}
