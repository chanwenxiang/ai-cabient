package com.aicabinet.common.dto;

import java.time.Instant;
import java.util.List;

public record OtaReleaseDto(
        Long releaseId,
        String appVersion,
        String channel,
        String downloadUrl,
        String objectStorageUri,
        String checksumSha256,
        String releaseNotes,
        boolean mandatory,
        String minVersion,
        String status,
        Instant publishedAt,
        int grayPercent,
        List<String> deviceAllowlist,
        int presignTtlSeconds
) {
    public OtaReleaseDto(Long releaseId, String appVersion, String channel, String downloadUrl,
                         String checksumSha256, String releaseNotes, boolean mandatory,
                         String minVersion, String status, Instant publishedAt) {
        this(releaseId, appVersion, channel, downloadUrl, null, checksumSha256, releaseNotes,
                mandatory, minVersion, status, publishedAt, 100, List.of(), 3600);
    }
}
