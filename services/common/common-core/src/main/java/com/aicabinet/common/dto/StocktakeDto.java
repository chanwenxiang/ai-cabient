package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

public record StocktakeDto(
        Long stocktakeId,
        String stocktakeNo,
        String warehouseId,
        String warehouseName,
        String mode,
        String status,
        int bookQty,
        int countedQty,
        int diffQty,
        int diffLineCount,
        Long operatorId,
        String notes,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        List<StocktakeLineDto> lines
) {}
