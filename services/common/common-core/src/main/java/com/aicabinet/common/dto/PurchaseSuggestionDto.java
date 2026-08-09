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
 * @param safetyStockQty 动态安全库存（按需求波动 × 服务水平 × √前置期估算）
 * @param forecastDailySales 预测日均销量（趋势模型下为未来覆盖期日均预测，否则等于 avgDailySales）
 * @param trendPerDay   日销量线性趋势（单位：件/天；未启用趋势模型时为 0）
 * @param suggestReason 建议理由：SALES_DRIVEN=销量驱动；TREND_FORECAST=趋势预测
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
        int safetyStockQty,
        double forecastDailySales,
        double trendPerDay,
        String suggestReason
) {}
