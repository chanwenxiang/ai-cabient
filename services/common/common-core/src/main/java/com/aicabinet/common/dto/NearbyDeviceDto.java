package com.aicabinet.common.dto;

import java.util.List;

/** 消费者附近柜机（含距离与库存预览）。 */
public record NearbyDeviceDto(
        String deviceId,
        String deviceName,
        String address,
        Double latitude,
        Double longitude,
        double distanceMeters,
        String onlineStatus,
        boolean available,
        int sellableSkuCount,
        int sellableItemCount,
        List<PreviewSku> previewSkus
) {
    public record PreviewSku(
            String skuId,
            String skuName,
            int quantity,
            int unitPriceCents
    ) {}
}
