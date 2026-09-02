package com.aicabinet.common.dto;

import jakarta.validation.constraints.Pattern;

/**
 * 按账期生成周期费用账单（租金 / 流量费等）。
 *
 * @param billMonth 账期 YYYY-MM；空则由服务按配置推算默认账期
 */
public record GenerateMonthlyFeeBillsRequest(
        @Pattern(regexp = "^(\\d{4}-\\d{2})?$", message = "billMonth 须为 YYYY-MM 或留空")
        String billMonth
) {
    public GenerateMonthlyFeeBillsRequest {
        if (billMonth != null) {
            billMonth = billMonth.isBlank() ? null : billMonth.trim();
        }
    }

    public static GenerateMonthlyFeeBillsRequest ofMonth(String billMonth) {
        return new GenerateMonthlyFeeBillsRequest(billMonth);
    }
}
