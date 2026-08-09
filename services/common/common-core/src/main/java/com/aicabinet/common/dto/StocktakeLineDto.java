package com.aicabinet.common.dto;

import java.time.Instant;
import java.time.LocalDate;

public record StocktakeLineDto(
        Long lineId,
        Long stocktakeId,
        String skuId,
        String skuName,
        String batchNo,
        LocalDate productionDate,
        LocalDate expiryDate,
        int bookQty,
        Integer countedQty,
        int diffQty,
        String status,
        String notes,
        Instant adjustedAt
) {}
