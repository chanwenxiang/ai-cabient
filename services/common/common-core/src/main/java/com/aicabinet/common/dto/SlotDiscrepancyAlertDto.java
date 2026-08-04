package com.aicabinet.common.dto;

import java.time.Instant;

/** 货道账实差异告警（账面批次汇总 vs 最近一次实测）。 */
public record SlotDiscrepancyAlertDto(
        String deviceId,
        String deviceName,
        String slotCode,
        String assignedSkuId,
        String assignedSkuName,
        int bookQty,
        int physicalQty,
        int qtyDiff,
        Instant lastPhysicalAt
) {}
