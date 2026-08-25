package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;

public record UpsertAdCampaignRequest(
        @NotBlank String name,
        String deviceScope,
        Instant startAt,
        Instant endAt,
        List<Long> assetIds,
        List<String> deviceIds
) {}
