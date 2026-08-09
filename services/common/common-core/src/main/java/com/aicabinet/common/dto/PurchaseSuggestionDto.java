package com.aicabinet.common.dto;

/**
 * 智能采购建议（以销定购）单行。
 *
 * @param skuId         商品编码
 * @param skuName       商品名称
 * @param onHandQty     仓库现有库存（含批次汇总）
 * @param pendingPoQty  待收采购量（已下单未收货）
 * @param soldQty7d     近 7 日销量（全部设备）
 * @param soldQty14d    近 14 日销量（全部设备）
 * @param avgDailySales 日均销量（7 日优先，其次 14 日）
 * @param coverageDays  建议覆盖天数（采购前置期 + 目标覆盖天数）
 * @param suggestQty    建议采购量
 * @param suggestReason 建议理由：SALES_DRIVEN=销量驱动
 */
public record PurchaseSuggestionDto(
        String skuId,
        String skuName,
        int onHandQty,
        int pendingPoQty,
        int soldQty7d,
        int soldQty14d,
        double avgDailySales,
        int coverageDays,
        int suggestQty,
        String suggestReason
) {}
