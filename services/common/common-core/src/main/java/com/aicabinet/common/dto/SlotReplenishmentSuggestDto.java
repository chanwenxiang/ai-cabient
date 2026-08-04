package com.aicabinet.common.dto;

public record SlotReplenishmentSuggestDto(
        String deviceId,
        String slotCode,
        String skuId,
        int bookQty,
        int parLevel,
        int minLevel,
        int maxLevel,
        int suggestQty,
        String stockStatus,
        int soldQty7d,
        int ropPoint,
        String suggestReason
) {
    public SlotReplenishmentSuggestDto(String deviceId, String slotCode, String skuId, int bookQty,
                                       int parLevel, int minLevel, int maxLevel, int suggestQty,
                                       String stockStatus) {
        this(deviceId, slotCode, skuId, bookQty, parLevel, minLevel, maxLevel,
                suggestQty, stockStatus, 0, 0, "PAR");
    }
}
