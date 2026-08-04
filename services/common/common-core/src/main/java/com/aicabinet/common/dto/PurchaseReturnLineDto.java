package com.aicabinet.common.dto;

public record PurchaseReturnLineDto(
        Long lineId,
        Long purchaseLineId,
        String skuId,
        String batchNo,
        int quantity
) {}
