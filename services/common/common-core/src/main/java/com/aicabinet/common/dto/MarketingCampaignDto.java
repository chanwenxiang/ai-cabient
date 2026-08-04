package com.aicabinet.common.dto;

import java.time.Instant;

public record MarketingCampaignDto(
        Long id,
        String title,
        String description,
        String type,
        String typeLabel,
        String coverColor,
        String coverEmoji,
        Instant startTime,
        Instant endTime,
        String status,
        String ctaLabel,
        String ctaPath,
        Boolean claimed,
        Boolean claimable
) {
    public MarketingCampaignDto(
            Long id,
            String title,
            String description,
            String type,
            String typeLabel,
            String coverColor,
            String coverEmoji,
            Instant startTime,
            Instant endTime,
            String status,
            String ctaLabel,
            String ctaPath
    ) {
        this(id, title, description, type, typeLabel, coverColor, coverEmoji,
                startTime, endTime, status, ctaLabel, ctaPath, null, null);
    }
}
