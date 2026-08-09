package com.aicabinet.common.dto;

import java.time.Instant;

public record WarehouseBinDto(
        Long binId,
        String warehouseId,
        String warehouseName,
        String binCode,
        String binName,
        String status,
        Instant createdAt
) {}
