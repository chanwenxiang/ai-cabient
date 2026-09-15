package com.aicabinet.common.dto;

import java.time.Instant;

public record DeviceInventoryDto(
        String deviceId,
        String skuId,
        int quantity,
        int capacity,
        int lowThreshold,
        Instant updatedAt,
        /** 汇总库存乐观锁版本；写接口应回传 expectedVersion。 */
        long inventoryVersion
) {
    public DeviceInventoryDto(
            String deviceId,
            String skuId,
            int quantity,
            int capacity,
            int lowThreshold,
            Instant updatedAt) {
        this(deviceId, skuId, quantity, capacity, lowThreshold, updatedAt, 0L);
    }
}
