package com.aicabinet.common.dto;

public record VideoClipDto(
        String camera,
        String videoUri,
        Long capturedAt
) {}
