package com.aicabinet.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record UpsertAdCampaignRequest(
        @NotBlank @Size(max = 128) String name,
        String deviceScope,
        String channel,
        @Size(max = 256) String linkUrl,
        Instant startAt,
        Instant endAt,
        List<Long> assetIds,
        List<String> deviceIds
) {}
