package com.aicabinet.common.dto;

public record OtaCheckResponse(
        boolean updateAvailable,
        String targetVersion,
        String downloadUrl,
        String checksumSha256,
        boolean mandatory,
        String releaseNotes
) {}
