package com.aicabinet.trade.domain;

import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeviceSlotId implements Serializable {

    private String deviceId;

    private String slotCode;

    public DeviceSlotId() {
    }

    public DeviceSlotId(String deviceId, String slotCode) {
        this.deviceId = deviceId;
        this.slotCode = slotCode;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeviceSlotId that)) return false;
        return Objects.equals(deviceId, that.deviceId) && Objects.equals(slotCode, that.slotCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, slotCode);
    }
}
