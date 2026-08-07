package com.aicabinet.common.dto;

/** 运营工作台聚合：一次请求返回统计 + 待办 + 待处理异常数。 */
public record OpsDashboardBundleDto(
        AdminStatsDto stats,
        OpsWorkbenchDto workbench,
        long openExceptionCount
) {}
