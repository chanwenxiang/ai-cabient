package com.aicabinet.common.dto;

public record MarketingBannerDto(
        Long id,
        String title,
        String subtitle,
        String tone,
        String emoji,
        Long campaignId,
        String ctaPath
) {}
