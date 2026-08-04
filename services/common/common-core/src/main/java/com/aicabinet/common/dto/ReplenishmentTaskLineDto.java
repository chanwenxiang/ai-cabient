package com.aicabinet.common.dto;

import java.time.LocalDate;

public record ReplenishmentTaskLineDto(
        Long lineId,
        String lineType,
        String skuId,
        String batchNo,
        LocalDate productionDate,
        LocalDate expiryDate,
        int quantity,
        String slotId,
        boolean applied
) {}
