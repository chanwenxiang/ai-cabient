package com.aicabinet.common.dto;

import java.time.LocalDate;
import java.util.List;

public record WarehouseInboundRequest(
        String warehouseId,
        String refNo,
        String notes,
        List<WarehouseInboundLineDto> lines
) {}
