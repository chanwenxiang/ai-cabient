package com.aicabinet.common.dto;

import java.util.List;

public record AdminOpsTrendDto(
        List<AdminOpsDailyDto> last7Days
) {}
