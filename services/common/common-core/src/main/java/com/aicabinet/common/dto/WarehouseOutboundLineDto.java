package com.aicabinet.common.dto;

import java.time.LocalDate;

/**
 * @param quantity     领货件数（从分仓带出多少，不是进柜多少）
 * @param remainderQty 领而未上件数 = quantity − 现场实收（CB-074）；0=全量上柜，
 *                     &gt;0 表示差额未结——货仍在补货员手上，既不在分仓账也不在柜机账
 */
public record WarehouseOutboundLineDto(
        Long lineId,
        String deviceId,
        String skuId,
        String batchNo,
        LocalDate expiryDate,
        int quantity,
        int remainderQty,
        boolean picked,
        String handoverStatus,
        String slotId,
        String deviceName
) {}
