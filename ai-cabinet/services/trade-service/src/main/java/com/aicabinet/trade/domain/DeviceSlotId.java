package com.aicabinet.trade.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class DeviceSlotId implements Serializable {

    @Column(name = "device_id", length = 64, nullable = false)
    private String deviceId;

    @Column(name = "slot_code", length = 32, nullable = false)
    private String slotCode;

    public DeviceSlotId() {
    }

    public DeviceSlotId(String deviceId, String slotCode) {
        this.deviceId = deviceId;
        this.slotCode = slotCode;
    }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getSlotCode() { return slotCode; }
    public void setSlotCode(String slotCode) { this.slotCode = slotCode; }

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
