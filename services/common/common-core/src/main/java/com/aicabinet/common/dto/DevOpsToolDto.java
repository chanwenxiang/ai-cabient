package com.aicabinet.common.dto;

public record DevOpsToolDto(
        String id,
        String name,
        String description,
        String url,
        String embedUrl,
        boolean online,
        String statusHint
) {}
