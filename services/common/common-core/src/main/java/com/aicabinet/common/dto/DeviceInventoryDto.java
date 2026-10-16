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
        long inventoryVersion,
        /** CB-051：上次配送后库存快照（补货完成后写入；null=无补货记录）。 */
        Integer lastRestockQty
) {
    public DeviceInventoryDto(
            String deviceId,
            String skuId,
            int quantity,
            int capacity,
            int lowThreshold,
            Instant updatedAt) {
        this(deviceId, skuId, quantity, capacity, lowThreshold, updatedAt, 0L, null);
    }

    public DeviceInventoryDto(
            String deviceId,
            String skuId,
            int quantity,
            int capacity,
            int lowThreshold,
            Instant updatedAt,
            long inventoryVersion) {
        this(deviceId, skuId, quantity, capacity, lowThreshold, updatedAt, inventoryVersion, null);
    }
}
