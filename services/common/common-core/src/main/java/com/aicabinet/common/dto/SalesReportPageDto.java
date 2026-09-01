package com.aicabinet.common.dto;

import java.util.List;

/**
 * 销售报表分页结果（含筛选条件下合计）。
 */
public record SalesReportPageDto(
        List<SalesReportRowDto> items,
        int page,
        int size,
        long total,
        SalesReportSummaryDto summary
) {}
