package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

public record WarehouseOutboundDto(
        Long outboundId,
        String warehouseId,
        Long routeId,
        String status,
        Long assigneeUserId,
        String notes,
        Instant createdAt,
        Instant shippedAt,
        List<WarehouseOutboundLineDto> lines
) {}
