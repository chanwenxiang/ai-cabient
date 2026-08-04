package com.aicabinet.common.dto;

import java.time.LocalDate;

public record DeviceSkuLotDto(
        String lotId,
        String deviceId,
        String skuId,
        String batchNo,
        LocalDate productionDate,
        LocalDate expiryDate,
        int quantity,
        String slotId,
        String status
) {}
