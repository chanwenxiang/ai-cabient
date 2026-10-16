package com.aicabinet.common.dto;

public record UpdateDeviceRequest(
        String deviceName,
        String deviceType,
        /** 投放场景（CB-049）：OFFICE/CAMPUS/...；null=不改，空串=清除 */
        String sceneType,
        String merchantId,
        /** AUTO_REFUND | DISPUTE_ONLY | INHERIT(清空覆盖，跟随全局) */
        String refundPolicy,
        String imei,
        String assetOwner,
        String coopMode,
        Long depositCents,
        Long dataFeeCents,
        /** 租赁月租金（分/月），仅 coopMode=RENTAL 生效（CB-034） */
        Long rentCents,
        /** 租赁起租账期（YYYY-MM） */
        String rentStartYm,
        String opsTags,
        String routeCode,
        String lifecycleRemark,
        Double latitude,
        Double longitude,
        String address,
        /** 所属分仓；空串清除，null 表示不改 */
        String homeWarehouseId
) {
    public UpdateDeviceRequest(String deviceName, String deviceType, String merchantId) {
        // 19 参：deviceName, deviceType, sceneType=null, merchantId, refundPolicy, imei, assetOwner,
        // coopMode, depositCents, dataFeeCents, rentCents, rentStartYm, opsTags, routeCode,
        // lifecycleRemark, latitude, longitude, address, homeWarehouseId
        this(deviceName, deviceType, null, merchantId, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public UpdateDeviceRequest(String deviceName, String deviceType, String merchantId, String refundPolicy) {
        this(deviceName, deviceType, null, merchantId, refundPolicy, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
