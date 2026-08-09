package com.aicabinet.common.dto;

import java.util.List;

public record FootfallAnalyticsDto(
        FootfallOverviewDto overview,
        List<FootfallDeviceDto> devices,
        List<HourlyHeatDto> hourly,
        List<SkuHeatDto> topSkus
) {}
