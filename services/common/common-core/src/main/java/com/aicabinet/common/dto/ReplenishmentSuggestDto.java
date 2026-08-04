package com.aicabinet.common.dto;

public record ReplenishmentSuggestDto(
        String deviceId,
        String skuId,
        int currentQty,
        int capacity,
        int lowThreshold,
        int suggestQty,
        int inTransitQty,
        int soldQty7d,
        int soldQty14d,
        int ropPoint,
        String suggestReason
) {
    public ReplenishmentSuggestDto(String deviceId, String skuId, int currentQty, int capacity,
                                   int lowThreshold, int suggestQty) {
        this(deviceId, skuId, currentQty, capacity, lowThreshold, suggestQty, 0, 0, 0, 0, "PAR");
    }
}
