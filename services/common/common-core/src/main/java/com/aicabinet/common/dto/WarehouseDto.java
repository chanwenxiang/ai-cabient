package com.aicabinet.common.dto;

import java.time.Instant;

public record WarehouseDto(
        String warehouseId,
        String warehouseName,
        String address,
        String status,
        Instant createdAt
) {}
