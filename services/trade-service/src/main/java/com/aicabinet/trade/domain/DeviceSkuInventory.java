package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import java.time.Instant;

@TableName("device_sku_inventory")
public class DeviceSkuInventory {

    @TableField(exist = false)
    private DeviceSkuInventoryId id;

    private String deviceId;

    private String skuId;

    private int quantity;

    private int capacity;

    private int lowThreshold = 2;

    private Instant updatedAt;

public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }
    public int getLowThreshold() { return lowThreshold; }
    public void setLowThreshold(int lowThreshold) { this.lowThreshold = lowThreshold; }
    public Instant getUpdatedAt() { return updatedAt; }

    public DeviceSkuInventoryId getId() {
        if (id == null && deviceId != null && skuId != null) {
            id = new DeviceSkuInventoryId(deviceId, skuId);
        }
        return id;
    }
    public void setId(DeviceSkuInventoryId id) {
        this.id = id;
        if (id != null) {
            this.deviceId = id.getDeviceId();
            this.skuId = id.getSkuId();
        }
    }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
}
