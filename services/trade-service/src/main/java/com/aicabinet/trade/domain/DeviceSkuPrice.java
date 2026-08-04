package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("device_sku_price")
public class DeviceSkuPrice {

    @TableField(exist = false)
    private DeviceSkuPriceId id;

    private String deviceId;

    private String skuId;

    @TableField("price_cents")
    private int priceCents;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("updated_by_user_id")
    private Long updatedByUserId;

    public DeviceSkuPriceId getId() {
        if (id == null && deviceId != null && skuId != null) {
            id = new DeviceSkuPriceId(deviceId, skuId);
        }
        return id;
    }

    public void setId(DeviceSkuPriceId id) {
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
    public int getPriceCents() { return priceCents; }
    public void setPriceCents(int priceCents) { this.priceCents = priceCents; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getUpdatedByUserId() { return updatedByUserId; }
    public void setUpdatedByUserId(Long updatedByUserId) { this.updatedByUserId = updatedByUserId; }
}
