package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("device_slot")
@Getter
@Setter
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

}
