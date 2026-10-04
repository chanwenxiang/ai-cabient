package com.aicabinet.common.dto;

/**
 * 分仓月结一行（件数，不是金额）。
 *
 * @param countedQty 本月最近一次已完成盘点的实盘件数；未盘则为 null
 */
public record WarehouseMonthlyCloseLineDto(
        String skuId,
        String skuName,
        int openingQty,
        int purchaseInQty,
        int transferInQty,
        int transferOutQty,
        int restockQty,
        int returnQty,
        int lossQty,
        int expectedQty,
        Integer countedQty,
        Integer gapQty
) {}
