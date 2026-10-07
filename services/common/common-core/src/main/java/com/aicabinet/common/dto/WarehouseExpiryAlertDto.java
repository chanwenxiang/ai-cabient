package com.aicabinet.common.dto;

import java.time.LocalDate;

/**
 * V320 仓库侧近效期预警行。
 *
 * <p>🔴 <b>为什么单独一个 DTO 而不是复用 {@code WarehouseInventoryDto}</b>：
 * 预警视图要回答的是「**还剩几天**」与「**有多急**」，
 * 而库存 DTO 只有 {@code expiryDate} —— 让运营自己减日期是不可用的。
 * 剩余天数放DB 侧算法有坑（时区/闰年），放前端又无法排序，
 * ⇒ 由后端算好给出。
 *
 * @param daysRemaining 距到期天数；**负数 = 已过期**（-3 表示 3 天前过期）
 * @param expired是否已过期（便于前端高亮，不必自己判断正负）
 * @param urgency 紧急度：EXPIRED / URGENT(≤7天) / SOON(≤30 天) / NORMAL
 */
public record WarehouseExpiryAlertDto(
        Long inventoryId,
        String warehouseId,
        String skuId,
        String batchNo,
        LocalDate expiryDate,
        int quantity,
        int daysRemaining,
        boolean expired,
        String urgency
) {}
