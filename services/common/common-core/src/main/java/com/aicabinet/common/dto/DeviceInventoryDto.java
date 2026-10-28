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
        Integer lastRestockQty,
        /** 商品名（读面注解；写接口忽略）。零货道机型补货明细以此作标题——
         * 无货道可注解名称，缺了只能退回 SKU 编号（2026-10-27 用户反馈）。 */
        String skuName,
        /** 商品图（读面注解；写接口忽略）。柜机详情「在架商品」列表展示，缺图前端兜底。 */
        String imageUrl
) {
    public DeviceInventoryDto(
            String deviceId,
            String skuId,
            int quantity,
            int capacity,
            int lowThreshold,
            Instant updatedAt) {
        this(deviceId, skuId, quantity, capacity, lowThreshold, updatedAt, 0L, null, null);
    }

    public DeviceInventoryDto(
            String deviceId,
            String skuId,
            int quantity,
            int capacity,
            int lowThreshold,
            Instant updatedAt,
            long inventoryVersion) {
        this(deviceId, skuId, quantity, capacity, lowThreshold, updatedAt, inventoryVersion, null, null);
    }

    /** 不带商品名的兼容构造（读面需要名字时用 10 参全参构造）。 */
    public DeviceInventoryDto(
            String deviceId,
            String skuId,
            int quantity,
            int capacity,
            int lowThreshold,
            Instant updatedAt,
            long inventoryVersion,
            Integer lastRestockQty) {
        this(deviceId, skuId, quantity, capacity, lowThreshold, updatedAt, inventoryVersion, lastRestockQty, null, null);
    }

    /** 带商品名、不带图的兼容构造（读面需要图时用 10 参全参构造）。 */
    public DeviceInventoryDto(
            String deviceId,
            String skuId,
            int quantity,
            int capacity,
            int lowThreshold,
            Instant updatedAt,
            long inventoryVersion,
            Integer lastRestockQty,
            String skuName) {
        this(deviceId, skuId, quantity, capacity, lowThreshold, updatedAt, inventoryVersion, lastRestockQty, skuName, null);
    }
}
