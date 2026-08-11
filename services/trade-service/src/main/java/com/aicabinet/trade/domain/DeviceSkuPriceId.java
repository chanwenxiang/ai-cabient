package com.aicabinet.trade.domain;

import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeviceSkuPriceId implements Serializable {

    private String deviceId;

    private String skuId;

    public DeviceSkuPriceId() {}

    public DeviceSkuPriceId(String deviceId, String skuId) {
        this.deviceId = deviceId;
        this.skuId = skuId;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeviceSkuPriceId that)) return false;
        return Objects.equals(deviceId, that.deviceId) && Objects.equals(skuId, that.skuId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, skuId);
    }
}
