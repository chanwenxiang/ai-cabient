package com.aicabinet.common.dto;

import java.util.List;

public record ScreenContentDto(
        Long campaignId,
        String campaignName,
        List<ScreenContentItemDto> items
) {}
