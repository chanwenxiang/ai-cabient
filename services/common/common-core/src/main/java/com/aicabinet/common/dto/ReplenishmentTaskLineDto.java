package com.aicabinet.common.dto;

import jakarta.validation.constraints.Min;

import java.time.LocalDate;

public record ReplenishmentTaskLineDto(
        Long lineId,
        String lineType,
        String skuId,
        String skuName,
        String batchNo,
        LocalDate productionDate,
        LocalDate expiryDate,
        @Min(0) int quantity,
        String slotId,
        boolean applied
) {}
