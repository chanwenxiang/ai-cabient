package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class DeviceSkuInventoryId implements Serializable {
    private String deviceId;
    private String skuId;

    public DeviceSkuInventoryId() {}

    public DeviceSkuInventoryId(String deviceId, String skuId) {
        this.deviceId = deviceId;
        this.skuId = skuId;
    }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeviceSkuInventoryId that)) return false;
        return Objects.equals(deviceId, that.deviceId) && Objects.equals(skuId, that.skuId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, skuId);
    }
}
