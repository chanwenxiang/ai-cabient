package com.aicabinet.common.dto;

public record UpdateDeviceRequest(
        String deviceName,
        String deviceType,
        String merchantId,
        /** AUTO_REFUND | DISPUTE_ONLY | INHERIT(清空覆盖，跟随全局) */
        String refundPolicy,
        String imei,
        String assetOwner,
        String coopMode,
        Long depositCents,
        Long dataFeeCents,
        String opsTags,
        String routeCode,
        String lifecycleRemark,
        Double latitude,
        Double longitude,
        String address
) {
    public UpdateDeviceRequest(String deviceName, String deviceType, String merchantId) {
        this(deviceName, deviceType, merchantId, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public UpdateDeviceRequest(String deviceName, String deviceType, String merchantId, String refundPolicy) {
        this(deviceName, deviceType, merchantId, refundPolicy, null, null, null, null, null, null, null, null, null, null, null);
    }
}
