package com.aicabinet.common.dto;

/** 补货运营台「缺货建议」行：账实差异 + 低库存货道合并去重。 */
public record ReplenishmentShortageRowDto(
        String deviceId,
        String deviceName,
        String slotCode,
        String skuId,
        String assignedSkuName,
        int bookQty,
        int minLevel,
        int parLevel,
        String stockStatus,
        String slotKey
) {}
