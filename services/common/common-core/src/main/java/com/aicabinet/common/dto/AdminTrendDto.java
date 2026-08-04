package com.aicabinet.common.dto;

import java.util.List;

public record AdminTrendDto(
        List<AdminDailyStatDto> last7Days
) {}
