package com.aicabinet.common.dto;

public record UpsertDeviceSlotRequest(
        String slotCode,
        Integer rowNo,
        Integer colNo,
        String slotType,
        String assignedSkuId,
        Integer parLevel,
        Integer minLevel,
        Integer maxLevel,
        Boolean enabled
) {}
