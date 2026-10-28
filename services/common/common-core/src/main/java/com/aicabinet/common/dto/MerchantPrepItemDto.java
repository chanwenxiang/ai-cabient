package com.aicabinet.common.dto;

/**
 * 柜机备货清单里的一行商品（CB-072 / CB-073）。
 * 数量口径=支（CB-061）；cases/remainUnits 由 pack_qty 换算（Units = Cases × pack_qty）。
 */
public record MerchantPrepItemDto(
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
        /** 本柜出库分仓的当前库存（支） */
        int stockUnits,
        int stockCases,
        /** 分仓库存是否不足以备齐（stockUnits < totalUnits） */
        boolean stockShort
) {}
