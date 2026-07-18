package com.aicabinet.common.dto;

public record UpdateDeviceRequest(
        String deviceName,
        String deviceType,
        String merchantId,
        /** AUTO_REFUND | DISPUTE_ONLY | INHERIT(清空覆盖，跟随全局) */
        String refundPolicy
) {
    public UpdateDeviceRequest(String deviceName, String deviceType, String merchantId) {
        this(deviceName, deviceType, merchantId, null);
    }
}
