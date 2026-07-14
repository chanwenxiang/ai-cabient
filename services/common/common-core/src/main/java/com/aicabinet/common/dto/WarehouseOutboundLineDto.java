package com.aicabinet.common.dto;

import java.time.LocalDate;

public record WarehouseOutboundLineDto(
        Long lineId,
        String deviceId,
        String skuId,
        String batchNo,
        LocalDate expiryDate,
        int quantity,
        boolean picked
) {}
