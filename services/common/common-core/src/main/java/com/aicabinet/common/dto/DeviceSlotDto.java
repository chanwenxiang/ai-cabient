package com.aicabinet.common.dto;

import java.time.Instant;

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
        boolean hasDiscrepancy
) {}
