package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import java.time.Instant;

@TableName("device_slot")
public class DeviceSlot {

    @TableField(exist = false)
    private DeviceSlotId id;

    private String deviceId;

    private String slotCode;

    private int rowNo = 1;

    private int colNo = 1;

    private String slotType = "SHELF";

    private String assignedSkuId;

    private int parLevel;

    private int minLevel;

    private int maxLevel;

    private boolean enabled = true;

    private Integer lastPhysicalQty;

    private Instant lastPhysicalAt;

    private Instant lastRestockAt;

    private Instant updatedAt;

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

    public DeviceSlotId getId() {
        if (id == null && deviceId != null && slotCode != null) {
            id = new DeviceSlotId(deviceId, slotCode);
        }
        return id;
    }
    public void setId(DeviceSlotId id) {
        this.id = id;
        if (id != null) {
            this.deviceId = id.getDeviceId();
            this.slotCode = id.getSlotCode();
        }
    }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getSlotCode() { return slotCode; }
    public void setSlotCode(String slotCode) { this.slotCode = slotCode; }
}
