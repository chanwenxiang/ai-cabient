package com.aicabinet.common.dto;

import java.time.LocalDate;

public record WarehouseOutboundLineDto(
        Long lineId,
        String deviceId,
        String skuId,
        String batchNo,
        LocalDate expiryDate,
        int quantity,
        boolean picked,
        String handoverStatus,
        String slotId,
        String deviceName
) {
    public WarehouseOutboundLineDto(
            Long lineId,
            String deviceId,
            String skuId,
            String batchNo,
            LocalDate expiryDate,
            int quantity,
            boolean picked,
            String handoverStatus,
            String slotId
    ) {
        this(lineId, deviceId, skuId, batchNo, expiryDate, quantity, picked, handoverStatus, slotId, null);
    }
}
