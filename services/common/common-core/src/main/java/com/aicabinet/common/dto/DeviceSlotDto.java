package com.aicabinet.common.dto;

import java.time.Instant;

/**
 * 货道陈列 DTO。V362 起补充图1（叮当小柜对照，2026-10-15）卡片墙所需字段：
 * SKU 图片/品牌/规格/条码、上架时间（assignedAt）、阈值口径缺货预警（stockoutWarn）。
 */
public record DeviceSlotDto(
        String deviceId,
        String slotCode,
        int rowNo,
        int colNo,
        String slotType,
        String assignedSkuId,
        String assignedSkuName,
        int parLevel,
        int minLevel,
        int maxLevel,
        boolean enabled,
        int bookQty,
        Integer lastPhysicalQty,
        Instant lastPhysicalAt,
        Instant lastRestockAt,
        int fillRatePct,
        String stockStatus,
        int qtyDiff,
        boolean hasDiscrepancy,
        String skuImageUrl,
        String skuBrand,
        String skuSpec,
        String skuBarcode,
        Instant assignedAt,
        boolean stockoutWarn
) {}
