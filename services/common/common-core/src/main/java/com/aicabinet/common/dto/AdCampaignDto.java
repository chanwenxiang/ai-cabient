package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

public record AdCampaignDto(
        Long campaignId,
        String name,
        String status,
        String deviceScope,
        Instant startAt,
        Instant endAt,
        List<Long> assetIds,
        List<String> deviceIds,
        Instant createdAt,
        Instant updatedAt,
        long impressionCount,
        long completeCount
) {
    public AdCampaignDto(Long campaignId, String name, String status, String deviceScope,
                         Instant startAt, Instant endAt, List<Long> assetIds, List<String> deviceIds,
                         Instant createdAt, Instant updatedAt) {
        this(campaignId, name, status, deviceScope, startAt, endAt, assetIds, deviceIds,
                createdAt, updatedAt, 0, 0);
    }
}
