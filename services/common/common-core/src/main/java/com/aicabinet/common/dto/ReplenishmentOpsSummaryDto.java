package com.aicabinet.common.dto;

/** 补货运营台顶栏 KPI 汇总（全量计数，非当前页）。 */
public record ReplenishmentOpsSummaryDto(
        long pendingTaskCount,
        long fulfilledTaskCount,
        long plannedRouteCount,
        long pendingRequestCount
) {}
