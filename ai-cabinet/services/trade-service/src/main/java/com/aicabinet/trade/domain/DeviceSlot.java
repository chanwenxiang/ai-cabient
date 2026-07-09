package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "device_slot")
public class DeviceSlot {

    @EmbeddedId
    private DeviceSlotId id;

    @Column(nullable = false)
    private int rowNo = 1;

    @Column(nullable = false)
    private int colNo = 1;

    @Column(nullable = false, length = 16)
    private String slotType = "SHELF";

    @Column(length = 64)
    private String assignedSkuId;

    @Column(nullable = false)
    private int parLevel;

    @Column(nullable = false)
    private int minLevel;

    @Column(nullable = false)
    private int maxLevel;

    @Column(nullable = false)
    private boolean enabled = true;

    private Integer lastPhysicalQty;

    private Instant lastPhysicalAt;

    private Instant lastRestockAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public DeviceSlotId getId() { return id; }
    public void setId(DeviceSlotId id) { this.id = id; }
    public int getRowNo() { return rowNo; }
    public void setRowNo(int rowNo) { this.rowNo = rowNo; }
    public int getColNo() { return colNo; }
    public void setColNo(int colNo) { this.colNo = colNo; }
    public String getSlotType() { return slotType; }
    public void setSlotType(String slotType) { this.slotType = slotType; }
    public String getAssignedSkuId() { return assignedSkuId; }
    public void setAssignedSkuId(String assignedSkuId) { this.assignedSkuId = assignedSkuId; }
    public int getParLevel() { return parLevel; }
    public void setParLevel(int parLevel) { this.parLevel = parLevel; }
    public int getMinLevel() { return minLevel; }
    public void setMinLevel(int minLevel) { this.minLevel = minLevel; }
    public int getMaxLevel() { return maxLevel; }
    public void setMaxLevel(int maxLevel) { this.maxLevel = maxLevel; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Integer getLastPhysicalQty() { return lastPhysicalQty; }
    public void setLastPhysicalQty(Integer lastPhysicalQty) { this.lastPhysicalQty = lastPhysicalQty; }
    public Instant getLastPhysicalAt() { return lastPhysicalAt; }
    public void setLastPhysicalAt(Instant lastPhysicalAt) { this.lastPhysicalAt = lastPhysicalAt; }
    public Instant getLastRestockAt() { return lastRestockAt; }
    public void setLastRestockAt(Instant lastRestockAt) { this.lastRestockAt = lastRestockAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
