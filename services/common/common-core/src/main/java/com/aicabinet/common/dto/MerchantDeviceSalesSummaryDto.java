package com.aicabinet.common.dto;

/**
 * 商户端柜机详情 KPI 汇总（CB-050，2026-10-16）：
 * 今日销售额/今日订单量/本月销售额（自然日/自然月，Asia/Shanghai；销售额=成交行收入口径）。
 */
public record MerchantDeviceSalesSummaryDto(
        long todaySalesCents,
        long todayOrders,
        long monthSalesCents
) {}
