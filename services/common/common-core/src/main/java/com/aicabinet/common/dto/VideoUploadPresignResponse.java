package com.aicabinet.common.dto;

public record VideoUploadPresignResponse(
        String objectKey,
        String uploadUrl,
        String videoUri,
        int expiresInSeconds
) {}
