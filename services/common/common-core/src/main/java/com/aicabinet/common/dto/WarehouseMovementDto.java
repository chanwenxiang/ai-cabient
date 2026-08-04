package com.aicabinet.common.dto;

import java.time.Instant;

public record WarehouseMovementDto(
        Long movementId,
        String warehouseId,
        String skuId,
        String batchNo,
        String movementType,
        int deltaQty,
        String refType,
        String refId,
        Long operatorId,
        Instant createdAt
) {}
