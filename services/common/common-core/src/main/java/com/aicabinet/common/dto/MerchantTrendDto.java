package com.aicabinet.common.dto;

import java.util.List;

public record MerchantTrendDto(
        List<MerchantDailyTrendDto> last7Days
) {}
