package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record ReceivePurchaseOrderRequest(
        @NotEmpty List<PurchaseOrderLineDto> lines,
        String notes,
        /** 可选：收货目标仓，默认采购单原 warehouseId */
        String receiveWarehouseId
) {
    public ReceivePurchaseOrderRequest(List<PurchaseOrderLineDto> lines, String notes) {
        this(lines, notes, null);
    }
}
