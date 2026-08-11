package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("device_sku_inventory")
@Getter
@Setter
public class DeviceSkuInventory {

    @TableField(exist = false)
    private DeviceSkuInventoryId id;

    private String deviceId;

    private String skuId;

    private int quantity;

    private int capacity;

    private int lowThreshold = 2;

    private Instant updatedAt;


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

}
