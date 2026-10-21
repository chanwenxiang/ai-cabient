package com.aicabinet.common.dto;

import java.time.LocalDate;

public record PurchaseOrderLineDto(
        Long lineId,
        String skuId,
        String batchNo,
        LocalDate productionDate,
        LocalDate expiryDate,
        int orderedQty,
        int receivedQty,
        int unitCostCents,
        int returnedQty,
        /** 件装数快照（CB-061）：下单时的每件支数，用于收货时件→支换算。 */
        int packQty
) {}
