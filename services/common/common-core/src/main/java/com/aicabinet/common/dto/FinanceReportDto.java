package com.aicabinet.common.dto;

import java.util.List;

public record FinanceReportDto(
        FinanceStatsDto summary,
        List<FinanceDailyDto> daily,
        List<FinanceSkuDto> topSkus
) {}
