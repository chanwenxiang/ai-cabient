package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("device_sku_price")
@Getter
@Setter
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

}
