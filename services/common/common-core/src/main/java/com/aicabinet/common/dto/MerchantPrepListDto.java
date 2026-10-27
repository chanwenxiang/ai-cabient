package com.aicabinet.common.dto;

import java.util.List;

/**
 * 商户首页「备货清单」行（CB-072）：本人范围内进行中补货任务的出库行按 SKU 聚合。
 * 数量口径=支（CB-061）；cases/remainUnits 由 pack_qty 换算（Units = Cases × pack_qty）。
 */
public record MerchantPrepListDto(
        String skuId,
        String skuName,
        /** SKU 最小单位（支/罐…），来自 sku_catalog.unit */
        String unit,
        int packQty,
        /** 需备总数量（支） */
        int totalUnits,
        /** 换算整件数（totalUnits / pack_qty 向下取整） */
        int cases,
        /** 拆零余数（totalUnits % pack_qty） */
        int remainUnits,
        /** 仓库当前库存（支，相关仓库合计） */
        int stockUnits,
        int stockCases,
        /** 仓库库存是否不足以备齐（stockUnits < totalUnits） */
        boolean stockShort,
        int taskCount,
        List<TaskRef> tasks
) {
    public record TaskRef(Long taskId, String deviceId, int quantity) {}
}
