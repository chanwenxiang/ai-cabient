package com.aicabinet.common.dto;

/**
 * 货道级热区：某货道（行列位置）在周期内的销量/营收与热度等级。
 */
public record SlotHeatDto(
        String slotId,
        int rowNo,
        int colNo,
        String skuId,
        String skuName,
        long qtySold,
        long revenueCents,
        int heatLevel
) {}
