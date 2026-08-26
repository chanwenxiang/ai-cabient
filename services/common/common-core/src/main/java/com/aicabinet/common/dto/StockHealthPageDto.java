package com.aicabinet.common.dto;

import java.util.List;

/**
 * 库存健康分页结果；KPI / 一键补货用全量过滤后的汇总，items 为当前页。
 */
public record StockHealthPageDto(
        List<StockHealthRowDto> items,
        int page,
        int size,
        long total,
        long stockoutCount,
        long lowCount,
        long nearExpiryCount,
        long deviceCount,
        List<String> planDeviceIds
) {}
