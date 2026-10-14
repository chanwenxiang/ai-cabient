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

    /** 乐观锁版本；与改价分布式锁并存。 */
    private long version;

    /**
     * 覆盖价是否启用（V350，CB-037 用户拍板语义 C：预设价可不立即生效）。
     * false=仅保存不参与取价，resolveUnitPriceCents 回落基准价；商户端写侧恒 true（行为不变）。
     */
    private boolean enabled = true;

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
